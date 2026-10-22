package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.service.WebhookEventCommandService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * 저장된 웹훅을 처리한다. 트랜잭션 없이 "점유(짧은 트랜잭션) → PG 재조회·원장 반영 → 결과 기록(짧은 트랜잭션)" 순서로 진행한다.
 * 스케줄러가 {@link #processPending(int)}를 주기적으로 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventProcessor {
    private final WebhookEventCommandService webhookEventCommandService;
    private final PaymentFacade paymentFacade;

    public void processPending(int limit) {
        for (Long eventId: webhookEventCommandService.findProcessableIds(Instant.now(), limit)) {
            try {
                process(eventId);
            } catch (Exception e) {
                log.error("[Webhook] 처리 중 예기치 못한 오류: eventId = {}", eventId, e);
            }
        }
    }

    public void process(Long eventId) {
        Optional<String> pgId;
        try {
            pgId = webhookEventCommandService.claim(eventId, Instant.now());
        } catch (PessimisticLockingFailureException e) {
            // 다른 인스턴스가 같은 웹훅을 잠그고 있으면 그쪽에 맡긴다.
            log.info("[Webhook] 다른 처리가 점유 중이라 건너뜀: eventId={}", eventId);
            return;
        }

        if (pgId.isEmpty()) {
            return;
        }

        try {
            PaymentSyncResult result = paymentFacade.syncPayment(pgId.get());
            webhookEventCommandService.recordResult(eventId, result, Instant.now());
            logResult(eventId, pgId.get(), result);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION) {
                // 멈춘 행으로 보고 다시 점유한 사이 다른 처리가 먼저 결과를 기록한 경우
                log.info("[Webhook] 다른 처리가 먼저 기록해 건너뜀: eventId={}", eventId);

                return;
            }
            recordFailure(eventId, pgId.get(), e);
        } catch (RuntimeException e) {
            recordFailure(eventId, pgId.get(), e);
        }
    }

    private void logResult(Long eventId, String pgId, PaymentSyncResult result) {
        switch (result) {
            case COMPENSATION_FAILED ->
                    log.error("[Webhook] PG 보상 취소 실패, 수동 환불 필요: eventId={}, pgId={}", eventId, pgId);
            case STATE_CONFLICT ->
                    log.error("[Webhook] 원장 상태와 충돌, 수동 확인 필요: eventId={}, pgId={}", eventId, pgId);
            default -> log.info("[Webhook] 처리 완료: eventId={}, pgId={}, result={}", eventId, pgId, result);
        }
    }

    // 일시적 오류(PG 조회 실패, 락 충돌, DB 오류 등)는 실패로 기록하고 백오프 후 재시도한다.
    private void recordFailure(Long eventId, String pgId, RuntimeException e) {
        log.warn("[Webhook] 처리 실패, 재시도 예정: eventId={}, pgId={}", eventId, pgId, e);
        webhookEventCommandService.recordFailure(eventId, e.getMessage(), Instant.now());
    }
}
