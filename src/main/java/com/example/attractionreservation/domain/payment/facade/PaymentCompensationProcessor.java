package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
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
 * 모든 메서드는 예외를 밖으로 던지지 않는다. 실패는 기록되어 재시도된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompensationProcessor {
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

    // 스케줄러에서 실행될 재처리할 id 가져와서 재처리
    public void processPending(int limit) {
        for (Long compensationId : paymentCompensationService.findProcessableIds(Instant.now(), limit)) {
            try {
                process(compensationId);
            } catch (RuntimeException e) {
                log.error("[Compensation] 처리 중 예기치 않은 오류: compensationId={}", compensationId, e);
            }
        }
    }

    // 재처리 메서드
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
            // PG 결제 전액을 돌려주는 전체 취소(amount = null). 다른 처리와 겹쳐 두 번 취소되면 두 번째는 "이미 취소됨"으로 성공 처리된다.
            paymentGateway.cancelPayment(compensation.pgId(), compensation.reason(), null);
            paymentCompensationService.recordDone(compensationId, Instant.now());
            log.info("[Compensation] PG 보상 취소 완료: compensationId={}, pgId={}, amount={}",
                    compensationId, compensation.pgId(), compensation.amount());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION) {
                // 멈춘 행으로 보고 다시 점유한 사이 다른 처리가 먼저 결과를 기록한 경우
                log.info("[Compensation] 다른 처리가 먼저 기록해 건너뜀: compensationId={}", compensationId);
                return;
            }
            recordFailure(compensation, e);
        } catch (RuntimeException e) {
            recordFailure(compensation, e);
        }
    }

    // 재처리 실패 시 기록
    private void recordFailure(ClaimedCompensation compensation, RuntimeException e) {
        CompensationStatus status = paymentCompensationService.recordFailure(compensation.id(), e.getMessage(), Instant.now());

        if (status == CompensationStatus.DEAD) {
            log.error("[Compensation] PG 보상 취소 최대 시도 초과, 수동 환불 필요: compensationId={}, pgId={}, amount={}",
                    compensation.id(), compensation.pgId(), compensation.amount(), e);
            return;
        }

        log.warn("[Compensation] PG 보상 취소 실패, 재시도 예정: compensationId={}, pgId={}",
                compensation.id(), compensation.pgId(), e);
    }
}
