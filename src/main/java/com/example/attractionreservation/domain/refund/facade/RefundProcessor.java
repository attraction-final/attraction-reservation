package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.refund.dto.ClaimedRefund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundCommandService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 환불의 PG 취소를 처리한다. 트랜잭션 없이 "점유(짧은 트랜잭션) → PG 부분 취소 → 결과 기록(짧은 트랜잭션)" 순서로 진행한다.
 *
 * <p>환불 요청이 커밋된 직후 {@link #processById(Long)}로 한 번 즉시 시도하고,
 * 실패하거나 시도 전에 서버가 종료된 환불은 스케줄러가 {@link #processPending(int, int)}로 이어받는다.
 * PG가 취소를 접수만 했거나 응답을 해석하지 못하면 PG_PENDING으로 두고, 취소를 다시 보내지 않고 PG 결제 조회로 확정한다.
 * PG 취소 처리({@link #processById(Long)}, {@link #processPending(int, int)})는 예외를 밖으로 던지지 않는다. PG 취소 실패는 기록되어 재시도된다.
 * 취소 웹훅의 환불 동기화({@link #syncWithPg(String)})는 웹훅이 다시 처리되도록 재시도가 필요한 오류를 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundProcessor {
    private static final String PG_CANCEL_FAILED_REASON = "PG 취소 실패 확인";

    private final RefundCommandService refundCommandService;
    private final PaymentGateway paymentGateway;

    // 환불 요청 커밋 직후 즉시 시도
    public void processById(Long refundId) {
        try {
            process(refundId);
        } catch (RuntimeException e) {
            // 즉시 시도가 실패해도 환불 요청은 남아 있으므로 스케줄러가 이어받는다.
            log.error("[Refund] 즉시 처리 중 예기치 않은 오류: refundId={}", refundId, e);
        }
    }

    /**
     * 스케줄러에서 실행. PG 취소 대상 환불을 처리한 뒤, PG 취소 결과를 확인할 환불을 처리한다.
     *
     * @param limit        한 번에 처리할 PG 취소 대상 수
     * @param pgCheckLimit 한 번에 확인할 PG_PENDING 수. 확인마다 PG를 조회하므로 PG 장애로 쌓여도 PG 취소 처리가 밀리지 않게 따로 제한한다
     */
    public void processPending(int limit, int pgCheckLimit) {
        for (Long refundId : refundCommandService.findProcessableIds(Instant.now(), limit)) {
            try {
                process(refundId);
            } catch (RuntimeException e) {
                log.error("[Refund] 처리 중 예기치 않은 오류: refundId={}", refundId, e);
            }
        }

        for (Long refundId : refundCommandService.findPgCheckableIds(Instant.now(), pgCheckLimit)) {
            try {
                confirmPending(refundId);
            } catch (RuntimeException e) {
                log.error("[Refund] PG 취소 확인 중 예기치 않은 오류: refundId={}", refundId, e);
            }
        }
    }

    // 점유 → (재시도면 이전 취소 반영 확인) → PG 부분 취소 → 결과 기록
    void process(Long refundId) {
        Optional<ClaimedRefund> claimed;
        try {
            claimed = refundCommandService.claim(refundId, Instant.now());
        } catch (PessimisticLockingFailureException e) {
            // 즉시 시도와 스케줄러, 또는 다른 인스턴스가 같은 결제를 잠그고 있으면 그쪽에 맡긴다.
            log.info("[Refund] 다른 처리가 점유 중이라 건너뜀: refundId={}", refundId);
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }

        ClaimedRefund refund = claimed.get();
        PaymentCancellationResponse result;
        try {
            // 재시도면 이전 취소가 이미 반영됐는지 먼저 확인하고, 반영되지 않았을 때만 취소를 보낸다.
            result = findAppliedCancellation(refund)
                    .orElseGet(() -> paymentGateway.cancelPayment(
                            refund.pgId(), refund.reason(), refund.amount(), refund.currentCancellableAmount()));
        } catch (RuntimeException e) {
            recordCancelFailure(refund, e);
            return;
        }

        if (result.isSucceeded()) {
            recordComplete(refund, result);
            return;
        }

        // REQUESTED·UNKNOWN: 취소가 반영됐을 수 있으므로 다시 보내지 않고 조회로 확정한다.
        recordPgPending(refund, result);
    }

    /**
     * PG_PENDING 환불의 PG 취소 결과를 조회로 확인해 기록한다.
     * 조회에 실패하거나 아직 확정되지 않았으면 다음 확인을 예약하고, 확인 기한이 지나면 DEAD로 보낸다.
     */
    void confirmPending(Long refundId) {
        Optional<ClaimedRefund> pending = refundCommandService.findPgCheckable(refundId, Instant.now());
        if (pending.isEmpty()) {
            return;
        }

        ClaimedRefund refund = pending.get();
        GatewayCancellationStatus result = checkPgCancellation(refund);

        try {
            recordCheckResult(refund, result);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                throw e;
            }
            // 다른 처리(웹훅·다른 인스턴스)가 먼저 확인 결과를 기록했거나, 그 뒤 재취소로 새 PG_PENDING 주기가 된 경우
            log.info("[Refund] 다른 처리가 먼저 PG 취소 확인을 기록해 건너뜀: refundId={}", refundId);
        }
    }

    /**
     * PG 취소 결과로 환불 상태를 맞춘다. 취소 웹훅 처리에서 사용한다.
     * PG를 다시 조회해 판단하며, 웹훅 본문은 사용하지 않는다.
     *
     * <p>확정 대기(PG_PENDING) 환불이 있으면 그 취소 내역으로 먼저 판단한다. 아직 확정되지 않았으면 {@code REFUND_PENDING}을
     * 반환하고 스케줄러가 이어서 확인한다. PG 취소 누적 금액에 접수만 된 취소가 포함되는지 알 수 없으므로 금액으로 판단하지 않는다.
     * 그 밖에는 취소된 누적 금액으로 미완료 환불을 맞춘다.
     *
     * <p>PG 조회 실패·락 충돌 등 재시도가 필요한 오류는 그대로 던져 웹훅이 다시 처리되게 한다.
     */
    public PaymentSyncResult syncWithPg(String pgId) {
        PaymentGatewayResponse pgPayment = paymentGateway.getPayment(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND));

        Optional<PaymentSyncResult> pendingResult = refundCommandService.findPgPendingByPgId(pgId)
                .flatMap(refund -> syncPgPending(refund, pgPayment));
        if (pendingResult.isPresent()) {
            return pendingResult.get();
        }

        long pgCancelledAmount = pgPayment.cancelledAmount() == null ? 0L : pgPayment.cancelledAmount();

        PaymentSyncResult result = refundCommandService.syncWithPgCancelledAmount(pgId, pgCancelledAmount, Instant.now());
        switch (result) {
            case REFUND_SYNCED -> log.info("[Refund] PG 취소 반영 확인, 환불 완료 처리: pgId={}, pgCancelledAmount={}",
                    pgId, pgCancelledAmount);
            // 수동 확인 알림은 이 로그 하나로 남긴다. 웹훅 쪽은 처리 결과만 info로 남긴다.
            case REFUND_MISMATCH -> log.error("[Refund] PG 취소 금액과 환불 불일치, 수동 확인 필요: pgId={}, pgCancelledAmount={}",
                    pgId, pgCancelledAmount);
            default -> {
            }
        }
        return result;
    }

    /**
     * 확정 대기 환불을 조회한 PG 결제로 판단해 기록한다. 웹훅이 스케줄러보다 확정을 앞당긴다.
     *
     * @return 완료로 기록했으면 REFUND_SYNCED, 아직 확정되지 않았으면 REFUND_PENDING.
     *         취소 실패로 기록했거나 다른 처리가 먼저 기록했으면 빈 값(PG 취소 금액으로 이어서 맞춘다)
     */
    private Optional<PaymentSyncResult> syncPgPending(ClaimedRefund refund, PaymentGatewayResponse pgPayment) {
        GatewayCancellationStatus result = judgePgCancellation(refund, pgPayment);
        if (result == GatewayCancellationStatus.REQUESTED) {
            log.info("[Refund] PG 취소 확정 대기 중, 스케줄러가 확인 예정: refundId={}, pgId={}, cancellationId={}",
                    refund.refundId(), refund.pgId(), refund.pgCancellationId());
            return Optional.of(PaymentSyncResult.REFUND_PENDING);
        }

        try {
            recordCheckResult(refund, result);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                throw e;
            }
            log.info("[Refund] 다른 처리가 먼저 PG 취소 확인을 기록함: refundId={}", refund.refundId());
            return Optional.empty();
        }

        return result == GatewayCancellationStatus.SUCCEEDED
                ? Optional.of(PaymentSyncResult.REFUND_SYNCED)
                : Optional.empty();
    }

    private void recordCheckResult(ClaimedRefund refund, GatewayCancellationStatus result) {
        switch (result) {
            case SUCCEEDED -> {
                refundCommandService.recordPgCancelled(refund.refundId(), refund.pgPendingSince(), Instant.now());
                log.info("[Refund] PG 취소 완료 확인: refundId={}, pgId={}, amount={}",
                        refund.refundId(), refund.pgId(), refund.amount());
            }
            case FAILED -> {
                RefundStatus status = refundCommandService.recordPgCancelFailed(
                        refund.refundId(), refund.pgPendingSince(), PG_CANCEL_FAILED_REASON, Instant.now());
                logFailureStatus(refund, status, null);
            }
            default -> {
                RefundStatus status = refundCommandService.reschedulePgCheck(
                        refund.refundId(), refund.pgPendingSince(), Instant.now());
                if (status == RefundStatus.DEAD) {
                    log.error("[Refund] PG 취소 확인 기한 초과, 수동 확인 필요: refundId={}, pgId={}, cancellationId={}, amount={}",
                            refund.refundId(), refund.pgId(), refund.pgCancellationId(), refund.amount());
                }
            }
        }
    }

    /**
     * 재시도 전에 이전 시도의 취소가 PG에 이미 반영됐는지 확인한다. 첫 시도는 보낸 취소가 없으므로 조회하지 않는다.
     *
     * <p>이전 시도가 취소를 보낸 뒤 결과를 기록하지 못했을 수 있다(서버 종료, 결과 기록 실패, 통신 실패).
     * 접수만 된 취소가 PG 취소 가능 금액에서 바로 빠지는지 알 수 없으므로, {@code currentCancellableAmount}만으로는
     * 같은 취소를 다시 보내는 것을 막을 수 없다. 금액이 같은 접수된 취소가 있으면 그 내역으로 확인 대기로 기록하고,
     * PG 취소 누적 금액에 이 환불이 반영됐으면 완료로 기록한다. 같은 결제의 환불은 한 번에 하나씩 처리하므로 금액으로 판단할 수 있다.
     * 조회에 실패하면 결과를 모른 채 취소를 다시 보내지 않도록 예외를 그대로 던져 실패로 기록되게 한다.
     *
     * @return 이미 반영된 취소. 없으면 빈 값(취소를 보낸다)
     */
    private Optional<PaymentCancellationResponse> findAppliedCancellation(ClaimedRefund refund) {
        if (refund.attemptCount() <= 1) {
            return Optional.empty();
        }

        Optional<PaymentGatewayResponse> pgPayment = paymentGateway.getPayment(refund.pgId());
        if (pgPayment.isEmpty()) {
            return Optional.empty();
        }

        Optional<PaymentCancellationResponse> requested = findRequestedCancellation(refund, pgPayment.get());
        if (requested.isPresent()) {
            log.info("[Refund] 재시도 전 확인: 접수된 PG 취소가 있어 다시 보내지 않음: refundId={}, pgId={}, cancellationId={}",
                    refund.refundId(), refund.pgId(), requested.get().cancellationId());
            return requested;
        }

        if (isCancelledAmountApplied(refund, pgPayment.get())) {
            log.info("[Refund] 재시도 전 확인: PG 취소 누적 금액에 이미 반영됨: refundId={}, pgId={}, amount={}",
                    refund.refundId(), refund.pgId(), refund.amount());
            return Optional.of(new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED));
        }

        return Optional.empty();
    }

    // PG 결제를 조회해 이 환불의 취소 결과를 판단한다. 조회할 수 없으면 REQUESTED(아직 확정 안 됨)로 본다.
    private GatewayCancellationStatus checkPgCancellation(ClaimedRefund refund) {
        Optional<PaymentGatewayResponse> pgPayment;
        try {
            pgPayment = paymentGateway.getPayment(refund.pgId());
        } catch (RuntimeException e) {
            log.warn("[Refund] PG 취소 확인 조회 실패, 다시 확인 예정: refundId={}, pgId={}",
                    refund.refundId(), refund.pgId(), e);
            return GatewayCancellationStatus.REQUESTED;
        }

        if (pgPayment.isEmpty()) {
            log.warn("[Refund] PG 취소 확인 중 PG 결제가 없음, 다시 확인 예정: refundId={}, pgId={}",
                    refund.refundId(), refund.pgId());
            return GatewayCancellationStatus.REQUESTED;
        }

        return judgePgCancellation(refund, pgPayment.get());
    }

    /**
     * 조회한 PG 결제로 이 환불의 취소 결과를 판단한다. SUCCEEDED·FAILED·REQUESTED(아직 확정 안 됨) 중 하나다.
     * 취소 내역 ID가 있으면 그 내역의 상태로 판단한다. 없으면(응답을 해석하지 못함) 금액이 같은 접수된 취소가 없고
     * PG 취소 누적 금액에 이 환불이 반영됐을 때만 완료로 본다. 실패는 확인할 수 없으므로 확인 기한까지 기다린다.
     */
    private GatewayCancellationStatus judgePgCancellation(ClaimedRefund refund, PaymentGatewayResponse pgPayment) {
        if (refund.pgCancellationId() != null) {
            GatewayCancellationStatus status = pgPayment.findCancellation(refund.pgCancellationId())
                    .map(PaymentCancellationResponse::status)
                    .orElse(GatewayCancellationStatus.REQUESTED);
            return status == GatewayCancellationStatus.UNKNOWN ? GatewayCancellationStatus.REQUESTED : status;
        }

        if (findRequestedCancellation(refund, pgPayment).isPresent()) {
            return GatewayCancellationStatus.REQUESTED;
        }

        return isCancelledAmountApplied(refund, pgPayment)
                ? GatewayCancellationStatus.SUCCEEDED
                : GatewayCancellationStatus.REQUESTED;
    }

    // PG가 접수했지만 아직 끝나지 않은 취소 중 이 환불과 금액이 같은 내역
    private Optional<PaymentCancellationResponse> findRequestedCancellation(
            ClaimedRefund refund, PaymentGatewayResponse pgPayment) {
        return pgPayment.cancellations().stream()
                .filter(cancellation -> cancellation.status() == GatewayCancellationStatus.REQUESTED)
                .filter(cancellation -> Objects.equals(cancellation.totalAmount(), refund.amount()))
                .findFirst();
    }

    // PG 취소 누적 금액에서 완료된 환불을 뺀 금액이 이 환불 금액과 같으면 이 환불의 취소가 반영된 것이다.
    private boolean isCancelledAmountApplied(ClaimedRefund refund, PaymentGatewayResponse pgPayment) {
        return pgPayment.cancelledAmount() != null
                && pgPayment.cancelledAmount() - refund.completedAmount() == refund.amount();
    }

    private void recordComplete(ClaimedRefund refund, PaymentCancellationResponse result) {
        try {
            refundCommandService.recordComplete(refund.refundId(), refund.attemptCount(), Instant.now());
            log.info("[Refund] PG 취소 완료: refundId={}, pgId={}, amount={}",
                    refund.refundId(), refund.pgId(), refund.amount());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                // 취소 웹훅이 먼저 완료했거나, 멈춘 처리로 보고 다른 처리가 다시 점유한 경우
                log.info("[Refund] 다른 처리가 먼저 기록해 건너뜀: refundId={}", refund.refundId());
                return;
            }
            recordCompletionFailure(refund, result, e);
        } catch (RuntimeException e) {
            recordCompletionFailure(refund, result, e);
        }
    }

    // PG 취소는 성공했으므로 실패로 기록하지 않는다. 실패로 기록하면 재시도에서 취소를 다시 보내게 된다.
    // 확인 대기로 기록해 조회로 완료되게 한다. 그것도 실패하면 PROCESSING으로 남고, 멈춘 처리 기준 시간이 지나
    // 다시 점유될 때 재시도 전 확인(findAppliedCancellation)이 PG 취소 누적 금액으로 완료 처리한다.
    private void recordCompletionFailure(ClaimedRefund refund, PaymentCancellationResponse result, RuntimeException e) {
        log.error("[Refund] PG 취소 성공 후 완료 기록 실패, 확인 대기로 전환: refundId={}, pgId={}, amount={}",
                refund.refundId(), refund.pgId(), refund.amount(), e);
        recordPgPending(refund, result);
    }

    private void recordPgPending(ClaimedRefund refund, PaymentCancellationResponse result) {
        try {
            refundCommandService.recordPgPending(
                    refund.refundId(), refund.attemptCount(), result.cancellationId(), Instant.now());
            log.info("[Refund] PG 취소 확정 대기, 조회로 확인 예정: refundId={}, pgId={}, cancellationId={}, status={}",
                    refund.refundId(), refund.pgId(), result.cancellationId(), result.status());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                log.info("[Refund] 다른 처리가 먼저 기록해 건너뜀: refundId={}", refund.refundId());
                return;
            }
            logPgPendingFailure(refund, result, e);
        } catch (RuntimeException e) {
            logPgPendingFailure(refund, result, e);
        }
    }

    // PROCESSING으로 남아 멈춘 처리 기준 시간이 지나 다시 점유될 때 재시도 전 확인이 접수된 취소를 찾아 확인 대기로 기록한다.
    private void logPgPendingFailure(ClaimedRefund refund, PaymentCancellationResponse result, RuntimeException e) {
        log.error("[Refund] PG 취소 확정 대기 기록 실패, 다시 점유될 때 확인 예정: refundId={}, pgId={}, cancellationId={}, amount={}",
                refund.refundId(), refund.pgId(), result.cancellationId(), refund.amount(), e);
    }

    // 취소 웹훅이 PG 기준으로 먼저 완료 처리한 환불이면 실패로 기록하지 않는다.
    private void recordCancelFailure(ClaimedRefund refund, RuntimeException e) {
        try {
            if (e instanceof BusinessException businessException && isNonRetryable(businessException.getErrorCode())) {
                recordDead(refund, businessException);
                return;
            }
            recordFailure(refund, e);
        } catch (BusinessException recordException) {
            if (recordException.getErrorCode() != ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                throw recordException;
            }
            log.info("[Refund] 다른 처리가 먼저 결과를 기록해 실패 기록 건너뜀: refundId={}", refund.refundId());
        }
    }

    // 앞선 취소가 PG에 반영됐거나 다른 경로로 전체 취소돼, 다시 보내도 같은 거절을 받는 실패
    private boolean isNonRetryable(ErrorCode errorCode) {
        return errorCode == ErrorCode.PAYMENT_ALREADY_CANCELLED
                || errorCode == ErrorCode.PAYMENT_CANCELLABLE_AMOUNT_MISMATCH;
    }

    private void recordDead(ClaimedRefund refund, BusinessException e) {
        refundCommandService.recordDead(refund.refundId(), refund.attemptCount(), e.getMessage());
        log.error("[Refund] PG에 이미 반영됐을 수 있어 재시도하지 않음, 수동 확인 필요: refundId={}, pgId={}, amount={}, errorCode={}",
                refund.refundId(), refund.pgId(), refund.amount(), e.getErrorCode(), e);
    }

    private void recordFailure(ClaimedRefund refund, RuntimeException e) {
        RefundStatus status = refundCommandService.recordFailure(
                refund.refundId(), refund.attemptCount(), e.getMessage(), Instant.now());
        logFailureStatus(refund, status, e);
    }

    private void logFailureStatus(ClaimedRefund refund, RefundStatus status, RuntimeException e) {
        if (status == RefundStatus.DEAD) {
            log.error("[Refund] PG 취소 최대 시도 초과, 수동 확인 필요: refundId={}, pgId={}, amount={}",
                    refund.refundId(), refund.pgId(), refund.amount(), e);
            return;
        }

        log.warn("[Refund] PG 취소 실패, 재시도 예정: refundId={}, pgId={}", refund.refundId(), refund.pgId(), e);
    }
}
