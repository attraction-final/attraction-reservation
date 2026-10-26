package com.example.attractionreservation.infra.portone.webhook;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import com.example.attractionreservation.infra.portone.webhook.dto.PortOneWebhookEvent;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.Webhook;
import io.portone.sdk.server.webhook.WebhookTransaction;
import io.portone.sdk.server.webhook.WebhookVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PortOneWebhookVerifier {
    private final WebhookVerifier webhookVerifier;

    // 시크릿은 "whsec_" 접두사를 뗀 뒤 Base64로 디코드되므로 형식이 틀리면 시작 시점에 실패한다.
    public PortOneWebhookVerifier(PortOneProperties properties) {
        this.webhookVerifier = new WebhookVerifier(properties.getWebhookSecret());
    }

    /**
     * 웹훅 서명과 타임스탬프(±5분)를 검증하고 이벤트 타입과 pgId를 꺼낸다.
     *
     * @throws BusinessException 서명·타임스탬프 검증에 실패하면 {@code PAYMENT_WEBHOOK_INVALID_SIGNATURE}
     */
    public PortOneWebhookEvent verify(String body, String webhookId, String signature, String timestamp) {
        // SDK는 null 인자에 검증 예외가 아닌 NPE를 던지므로 먼저 막는다.
        if (body == null || webhookId == null || signature == null || timestamp == null) {
            log.warn("PortOne 웹훅 검증 실패: 필수 헤더 누락, webhookId={}", webhookId);

            throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
        }

        Webhook webhook;
        try {
            webhook = webhookVerifier.verify(body, webhookId, signature, timestamp);
        } catch (WebhookVerificationException e) {
            log.warn("PortOne 웹훅 검증 실패: webhookId={}, reason={}", webhookId, e.getMessage());

            throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE);
        }

        String pgId = webhook instanceof WebhookTransaction transaction
                ? transaction.getData().getPaymentId()
                : null;

        return new PortOneWebhookEvent(webhook.getClass().getSimpleName(), pgId);
    }
}
