package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.domain.payment.service.WebhookEventCommandService;
import com.example.attractionreservation.domain.refund.facade.RefundProcessor;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 저장된 웹훅을 처리한다. 트랜잭션 없이 "점유(짧은 트랜잭션) → PG 재조회·원장 반영 → 결과 기록(짧은 트랜잭션)" 순서로 진행한다.
 * 결제 완료·환불된 결제는 {@link RefundProcessor#syncWithPg(String)}로 PG 취소 금액을 환불 상태에 반영한다(취소 웹훅).
 * 스케줄러가 {@link #processPending(int)}를 주기적으로 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventProcessor {
    private final WebhookEventCommandService webhookEventCommandService;
    private final PaymentFacade paymentFacade;
    private final RefundProcessor refundProcessor;

    /**
     * @return 이번 주기에 처리를 시도한 웹훅 수
     */
    public int processPending(int limit) {
        List<Long> eventIds = webhookEventCommandService.findProcessableIds(Instant.now(), limit);
        for (Long eventId: eventIds) {
            try {
                process(eventId);
            } catch (Exception e) {
                log.error("event=WEBHOOK_PROCESS_ABORTED eventId={}", eventId, e);
            }
        }
        return eventIds.size();
    }

    public void process(Long eventId) {
        Optional<String> pgId;
        try {
            pgId = webhookEventCommandService.claim(eventId, Instant.now());
        } catch (PessimisticLockingFailureException e) {
            // 다른 인스턴스가 같은 웹훅을 잠그고 있으면 그쪽에 맡긴다.
            log.info("event=WEBHOOK_PROCESS_SKIPPED eventId={} reason=\"다른 처리가 점유 중\"", eventId);
            return;
        }

        if (pgId.isEmpty()) {
            return;
        }

        try {
            PaymentSyncResult result = paymentFacade.syncPayment(pgId.get());

            if (result == PaymentSyncResult.REFUND_SYNC_REQUIRED) {
                result = refundProcessor.syncWithPg(pgId.get());
            }

            webhookEventCommandService.recordResult(eventId, result, Instant.now());
            logResult(eventId, pgId.get(), result);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION) {
                // 멈춘 행으로 보고 다시 점유한 사이 다른 처리가 먼저 결과를 기록한 경우
                log.info("event=WEBHOOK_PROCESS_SKIPPED eventId={} reason=\"다른 처리가 먼저 기록\"", eventId);

                return;
            }
            recordFailure(eventId, pgId.get(), e);
        } catch (RuntimeException e) {
            recordFailure(eventId, pgId.get(), e);
        }
    }

    private void logResult(Long eventId, String pgId, PaymentSyncResult result) {
        switch (result) {
            case STATE_CONFLICT ->
                    log.error("event=WEBHOOK_STATE_CONFLICTED eventId={} pgId={}", eventId, pgId);
            default -> log.info("event=WEBHOOK_PROCESSED eventId={} pgId={} result={}", eventId, pgId, result);
        }
    }

    // 일시적 오류(PG 조회 실패, 락 충돌, DB 오류 등)는 실패로 기록하고 백오프 후 재시도한다.
    private void recordFailure(Long eventId, String pgId, RuntimeException e) {
        WebhookStatus status;
        try {
            status = webhookEventCommandService.recordFailure(eventId, e.getMessage(), Instant.now());
        } catch (RuntimeException recordError) {
            // 기록 실패 예외만 위로 전파되므로 원래 처리 실패 원인을 여기서 남긴다.
            // 대부분 롤백돼 처리 중으로 남고 재점유로 재시도되지만, 다른 처리가 먼저 상태를 바꿨거나 커밋 결과가 불확실할 수 있어 단정하지 않는다.
            log.error("event=WEBHOOK_FAILURE_RECORD_FAILED eventId={} pgId={} errorCode={}",
                    eventId, pgId, errorCodeOf(e), unexpectedOrNull(e));
            throw recordError;
        }

        if (status == WebhookStatus.DEAD) {
            log.error("event=WEBHOOK_RETRY_EXHAUSTED eventId={} pgId={} errorCode={}",
                    eventId, pgId, errorCodeOf(e), unexpectedOrNull(e));
            return;
        }
        log.warn("event=WEBHOOK_PROCESS_FAILED eventId={} pgId={} errorCode={}",
                eventId, pgId, errorCodeOf(e), unexpectedOrNull(e));
    }

    // BusinessException은 예상된 실패라 errorCode만 남긴다. PG 연동 실패는 PortOneClient가 원인 예외와 함께 이미 남겼다.
    // 예상하지 못한 예외만 스택 트레이스를 남긴다.
    private String errorCodeOf(RuntimeException e) {
        return e instanceof BusinessException businessException ? businessException.getErrorCode().name() : "";
    }

    private RuntimeException unexpectedOrNull(RuntimeException e) {
        return e instanceof BusinessException ? null : e;
    }
}
