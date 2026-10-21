package com.example.attractionreservation.domain.payment.scheduler;

import com.example.attractionreservation.domain.payment.facade.WebhookEventProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장된 웹훅을 주기적으로 처리한다.
 * 이전 실행이 끝난 뒤 fixed-delay만큼 기다리므로 실행이 겹치지 않는다.
 * 여러 인스턴스에서 동시에 돌아도 Processor가 웹훅을 잠그고 상태를 다시 확인해 중복 처리를 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.webhook.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookEventScheduler {
    private final WebhookEventProcessor webhookEventProcessor;
    private final int batchSize;

    public WebhookEventScheduler(
            WebhookEventProcessor webhookEventProcessor,
            @Value("${payment.webhook.scheduler.batch-size:50}") int batchSize
    ) {
        this.webhookEventProcessor = webhookEventProcessor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.webhook.scheduler.fixed-delay:10s}")
    public void processPendingWebhooks() {
        try {
            webhookEventProcessor.processPending(batchSize);
        } catch (RuntimeException e) {
            // 처리 대상 조회 실패 등. 다음 주기에 다시 시도한다.
            log.error("[Webhook] 스케줄러 실행 실패", e);
        }
    }
}
