package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
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
import java.util.Optional;

/**
 * 환불의 PG 취소를 처리한다. 트랜잭션 없이 "점유(짧은 트랜잭션) → PG 부분 취소 → 결과 기록(짧은 트랜잭션)" 순서로 진행한다.
 *
 * <p>환불 요청이 커밋된 직후 {@link #processById(Long)}로 한 번 즉시 시도하고,
 * 실패하거나 시도 전에 서버가 종료된 환불은 스케줄러가 {@link #processPending(int)}로 이어받는다.
 * PG 취소 처리({@link #processById(Long)}, {@link #processPending(int)})는 예외를 밖으로 던지지 않는다. PG 취소 실패는 기록되어 재시도된다.
 * 취소 웹훅의 환불 동기화({@link #syncWithPg(String)})는 웹훅이 다시 처리되도록 재시도가 필요한 오류를 던진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundProcessor {
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

    // 스케줄러에서 처리 대상 환불을 가져와 처리
    public void processPending(int limit) {
        for (Long refundId : refundCommandService.findProcessableIds(Instant.now(), limit)) {
            try {
                process(refundId);
            } catch (RuntimeException e) {
                log.error("[Refund] 처리 중 예기치 않은 오류: refundId={}", refundId, e);
            }
        }
    }

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
        try {
            paymentGateway.cancelPayment(
                    refund.pgId(), refund.reason(), refund.amount(), refund.currentCancellableAmount());
        } catch (RuntimeException e) {
            recordCancelFailure(refund, e);
            return;
        }

        try {
            refundCommandService.recordComplete(refund.refundId(), Instant.now());
            log.info("[Refund] PG 취소 완료: refundId={}, pgId={}, amount={}",
                    refund.refundId(), refund.pgId(), refund.amount());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.REFUND_INVALID_STATUS_TRANSITION) {
                // 멈춘 행으로 보고 다시 점유한 사이 다른 처리가 먼저 결과를 기록한 경우
                log.info("[Refund] 다른 처리가 먼저 기록해 건너뜀: refundId={}", refund.refundId());
                return;
            }
            logCompletionFailure(refund, e);
        } catch (RuntimeException e) {
            logCompletionFailure(refund, e);
        }
    }

    /**
     * PG 취소 결과로 환불 상태를 맞춘다. 취소 웹훅 처리에서 사용한다.
     * PG를 다시 조회해 취소된 누적 금액으로 판단하며, 웹훅 본문은 사용하지 않는다.
     *
     * <p>PG 조회 실패·락 충돌 등 재시도가 필요한 오류는 그대로 던져 웹훅이 다시 처리되게 한다.
     */
    public PaymentSyncResult syncWithPg(String pgId) {
        PaymentGatewayResponse pgPayment = paymentGateway.getPayment(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND));
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
        refundCommandService.recordDead(refund.refundId(), e.getMessage());
        log.error("[Refund] PG에 이미 반영됐을 수 있어 재시도하지 않음, 수동 확인 필요: refundId={}, pgId={}, amount={}, errorCode={}",
                refund.refundId(), refund.pgId(), refund.amount(), e.getErrorCode(), e);
    }

    private void recordFailure(ClaimedRefund refund, RuntimeException e) {
        RefundStatus status = refundCommandService.recordFailure(refund.refundId(), e.getMessage(), Instant.now());

        if (status == RefundStatus.DEAD) {
            log.error("[Refund] PG 취소 최대 시도 초과, 수동 확인 필요: refundId={}, pgId={}, amount={}",
                    refund.refundId(), refund.pgId(), refund.amount(), e);
            return;
        }

        log.warn("[Refund] PG 취소 실패, 재시도 예정: refundId={}, pgId={}", refund.refundId(), refund.pgId(), e);
    }

    // PG 취소는 성공했으므로 실패로 기록하지 않는다. 실패로 기록하면 재시도에서 취소를 다시 보내게 된다.
    // PROCESSING으로 남아 취소 웹훅이 오면 PG 기준으로 완료되고(syncWithPg), 오지 않으면 멈춘 처리 기준 시간이 지나 다시 점유된 뒤
    // PG 취소 가능 금액이 달라 거절되어 DEAD가 된다.
    private void logCompletionFailure(ClaimedRefund refund, RuntimeException e) {
        log.error("[Refund] PG 취소 성공 후 완료 기록 실패, 확인 필요: refundId={}, pgId={}, amount={}",
                refund.refundId(), refund.pgId(), refund.amount(), e);
    }
}
