package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.service.PaymentCompensationService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * PG 보상 취소 요청(Outbox)을 처리한다.
 * 트랜잭션 없이 "점유(짧은 트랜잭션) → PG 취소 → 결과 기록(짧은 트랜잭션)" 순서로 진행한다.
 *
 * <p>보상 요청이 커밋된 직후 {@link #processByPgId(String)}로 한 번 즉시 시도하고,
 * 실패하거나 시도 전에 서버가 종료된 요청은 스케줄러가 {@link #processPending(int)}로 이어받는다.
 *
 * <p>PG가 취소를 접수만 했거나(REQUESTED) 결과를 해석할 수 없으면 PG_PENDING으로 두고, 취소를 다시 보내지 않는다.
 * 스케줄러가 PG 결제를 조회해 취소 내역으로 확정한다. 웹훅 수신 여부와 관계없이 이 조회가 확정을 책임진다.
 * 모든 메서드는 예외를 밖으로 던지지 않는다. 실패는 기록되어 재시도된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationProcessor {
    private static final String PG_CANCEL_FAILED_REASON = "PG 취소 실패 확인";

    private final PaymentCompensationService paymentCompensationService;
    private final PaymentGateway paymentGateway;

    // 보상 요청 커밋 직후 즉시 시도
    public void processByPgId(String pgId) {
        try {
            paymentCompensationService.findIdByPgId(pgId).ifPresent(this::process);
        } catch (RuntimeException e) {
            // 즉시 시도가 실패해도 보상 요청은 남아 있으므로 스케줄러가 이어받는다.
            log.error("[Compensation] 즉시 처리 중 예기치 않은 오류: pgId={}", pgId, e);
        }
    }

    // 스케줄러에서 실행. PG 취소 대상을 처리한 뒤, PG 취소 결과를 확인할 대상을 처리한다.
    public void processPending(int limit) {
        for (Long compensationId : paymentCompensationService.findProcessableIds(Instant.now(), limit)) {
            try {
                process(compensationId);
            } catch (RuntimeException e) {
                log.error("[Compensation] 처리 중 예기치 않은 오류: compensationId={}", compensationId, e);
            }
        }

        for (Long compensationId : paymentCompensationService.findPgCheckableIds(Instant.now(), limit)) {
            try {
                confirmPending(compensationId);
            } catch (RuntimeException e) {
                log.error("[Compensation] PG 취소 확인 중 예기치 않은 오류: compensationId={}", compensationId, e);
            }
        }
    }

    // 점유 → PG 취소 → 결과 기록
    void process(Long compensationId) {
        Optional<ClaimedCompensation> claimed;
        try {
            claimed = paymentCompensationService.claim(compensationId, Instant.now());
        } catch (PessimisticLockingFailureException e) {
            // 즉시 시도와 스케줄러, 또는 다른 인스턴스가 같은 요청을 잠그고 있으면 그쪽에 맡긴다.
            log.info("[Compensation] 다른 처리가 점유 중이라 건너뜀: compensationId={}", compensationId);
            return;
        }

        if (claimed.isEmpty()) {
            return;
        }

        ClaimedCompensation compensation = claimed.get();
        try {
            // 재시도면 이전 취소가 이미 반영됐는지 먼저 확인하고, 반영되지 않았을 때만 취소를 보낸다.
            // PG 결제 전액을 돌려주는 전체 취소(amount = null). 다른 처리와 겹쳐 두 번 취소되면 두 번째는 "이미 취소됨"으로 성공 처리된다.
            PaymentCancellationResponse result = findAppliedCancellation(compensation)
                    .orElseGet(() -> paymentGateway.cancelPayment(compensation.pgId(), compensation.reason(), null));

            if (result.isSucceeded()) {
                paymentCompensationService.recordDone(compensationId, compensation.attemptCount(), Instant.now());
                log.info("[Compensation] PG 보상 취소 완료: compensationId={}, pgId={}, amount={}",
                        compensationId, compensation.pgId(), compensation.amount());
                return;
            }

            // REQUESTED·UNKNOWN: 취소가 반영됐을 수 있으므로 다시 보내지 않고 조회로 확정한다.
            paymentCompensationService.recordPgPending(
                    compensationId, compensation.attemptCount(), result.cancellationId(), Instant.now());
            log.info("[Compensation] PG 취소 확정 대기, 조회로 확인 예정: compensationId={}, pgId={}, cancellationId={}, status={}",
                    compensationId, compensation.pgId(), result.cancellationId(), result.status());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION) {
                // 멈춘 행으로 보고 다시 점유한 사이 다른 처리가 먼저 결과를 기록했거나 다시 점유한 경우
                log.info("[Compensation] 다른 처리가 먼저 기록해 건너뜀: compensationId={}", compensationId);
                return;
            }
            recordFailure(compensation, e);
        } catch (RuntimeException e) {
            recordFailure(compensation, e);
        }
    }

    /**
     * PG_PENDING 요청의 PG 취소 결과를 조회로 확인해 기록한다.
     * 조회에 실패하거나 아직 확정되지 않았으면 다음 확인을 예약하고, 확인 기한이 지나면 DEAD로 보낸다.
     */
    void confirmPending(Long compensationId) {
        Optional<ClaimedCompensation> pending = paymentCompensationService.findPgCheckable(compensationId, Instant.now());
        if (pending.isEmpty()) {
            return;
        }

        ClaimedCompensation compensation = pending.get();
        GatewayCancellationStatus result = checkPgCancellation(compensation);

        try {
            switch (result) {
                case SUCCEEDED -> {
                    paymentCompensationService.recordPgCancelled(
                            compensationId, compensation.pgPendingSince(), Instant.now());
                    log.info("[Compensation] PG 취소 완료 확인: compensationId={}, pgId={}, amount={}",
                            compensationId, compensation.pgId(), compensation.amount());
                }
                case FAILED -> {
                    CompensationStatus status = paymentCompensationService.recordPgCancelFailed(
                            compensationId, compensation.pgPendingSince(), PG_CANCEL_FAILED_REASON, Instant.now());
                    logFailureStatus(compensation, status, null);
                }
                default -> {
                    CompensationStatus status = paymentCompensationService.reschedulePgCheck(
                            compensationId, compensation.pgPendingSince(), Instant.now());
                    if (status == CompensationStatus.DEAD) {
                        log.error("[Compensation] PG 취소 확인 기한 초과, 수동 확인 필요: compensationId={}, pgId={}, cancellationId={}, amount={}",
                                compensationId, compensation.pgId(), compensation.pgCancellationId(), compensation.amount());
                    }
                }
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION) {
                throw e;
            }
            // 다른 처리가 먼저 확인 결과를 기록했거나, 그 뒤 재취소로 새 PG_PENDING 주기가 된 경우
            log.info("[Compensation] 다른 처리가 먼저 PG 취소 확인을 기록해 건너뜀: compensationId={}", compensationId);
        }
    }

    /**
     * 재시도 전에 이전 시도의 취소가 PG에 이미 반영됐는지 확인한다. 첫 시도는 보낸 취소가 없으므로 조회하지 않는다.
     *
     * <p>이전 시도가 취소를 보낸 뒤 결과를 기록하지 못했을 수 있다(서버 종료, 결과 기록 실패, 통신 실패, 처리 중인 멱등 키 충돌).
     * PG 결제가 이미 전체 취소됐으면 완료로, 접수만 된 취소가 있으면 그 내역으로 확인 대기로 기록하고 취소를 다시 보내지 않는다.
     * 조회에 실패하면 결과를 모른 채 취소를 다시 보내지 않도록 예외를 그대로 던져 실패로 기록되게 한다.
     *
     * @return 이미 반영된 취소. 없으면 빈 값(취소를 보낸다)
     */
    private Optional<PaymentCancellationResponse> findAppliedCancellation(ClaimedCompensation compensation) {
        if (compensation.attemptCount() <= 1) {
            return Optional.empty();
        }

        Optional<PaymentGatewayResponse> pgPayment = paymentGateway.getPayment(compensation.pgId());
        if (pgPayment.isEmpty()) {
            return Optional.empty();
        }

        if (pgPayment.get().isCancelled()) {
            log.info("[Compensation] 재시도 전 확인: PG 결제가 이미 취소됨: compensationId={}, pgId={}",
                    compensation.id(), compensation.pgId());
            return Optional.of(new PaymentCancellationResponse(null, GatewayCancellationStatus.SUCCEEDED));
        }

        Optional<PaymentCancellationResponse> requested = pgPayment.get().findRequestedCancellation();
        requested.ifPresent(cancellation ->
                log.info("[Compensation] 재시도 전 확인: 접수된 PG 취소가 있어 다시 보내지 않음: compensationId={}, pgId={}, cancellationId={}",
                        compensation.id(), compensation.pgId(), cancellation.cancellationId()));
        return requested;
    }

    /**
     * PG 결제를 조회해 이 보상의 취소 결과를 판단한다. 판단할 수 없으면 REQUESTED(아직 확정 안 됨)로 본다.
     * 취소 내역 ID가 있으면 그 내역의 상태로, 없으면(응답을 해석하지 못함) 결제가 전체 취소됐는지로 판단한다.
     */
    private GatewayCancellationStatus checkPgCancellation(ClaimedCompensation compensation) {
        Optional<PaymentGatewayResponse> pgPayment;
        try {
            pgPayment = paymentGateway.getPayment(compensation.pgId());
        } catch (RuntimeException e) {
            log.warn("[Compensation] PG 취소 확인 조회 실패, 다시 확인 예정: compensationId={}, pgId={}",
                    compensation.id(), compensation.pgId(), e);
            return GatewayCancellationStatus.REQUESTED;
        }

        if (pgPayment.isEmpty()) {
            log.warn("[Compensation] PG 취소 확인 중 PG 결제가 없음, 다시 확인 예정: compensationId={}, pgId={}",
                    compensation.id(), compensation.pgId());
            return GatewayCancellationStatus.REQUESTED;
        }

        if (compensation.pgCancellationId() == null) {
            return pgPayment.get().isCancelled() ? GatewayCancellationStatus.SUCCEEDED : GatewayCancellationStatus.REQUESTED;
        }

        GatewayCancellationStatus status = pgPayment.get().findCancellation(compensation.pgCancellationId())
                .map(PaymentCancellationResponse::status)
                .orElse(GatewayCancellationStatus.REQUESTED);
        return status == GatewayCancellationStatus.UNKNOWN ? GatewayCancellationStatus.REQUESTED : status;
    }

    // 재처리 실패 시 기록
    private void recordFailure(ClaimedCompensation compensation, RuntimeException e) {
        CompensationStatus status;
        try {
            status = paymentCompensationService.recordFailure(
                    compensation.id(), compensation.attemptCount(), e.getMessage(), Instant.now());
        } catch (BusinessException recordException) {
            if (recordException.getErrorCode() != ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION) {
                throw recordException;
            }
            // 이 처리가 멈춘 것으로 보고 다른 처리가 다시 점유했다. 늦은 실패 결과로 그 처리를 덮어쓰지 않는다.
            log.info("[Compensation] 다른 처리가 다시 점유해 실패 기록 건너뜀: compensationId={}", compensation.id(), e);
            return;
        }
        logFailureStatus(compensation, status, e);
    }

    private void logFailureStatus(ClaimedCompensation compensation, CompensationStatus status, RuntimeException e) {
        if (status == CompensationStatus.DEAD) {
            log.error("[Compensation] PG 보상 취소 최대 시도 초과, 수동 환불 필요: compensationId={}, pgId={}, amount={}",
                    compensation.id(), compensation.pgId(), compensation.amount(), e);
            return;
        }

        log.warn("[Compensation] PG 보상 취소 실패, 재시도 예정: compensationId={}, pgId={}",
                compensation.id(), compensation.pgId(), e);
    }
}
