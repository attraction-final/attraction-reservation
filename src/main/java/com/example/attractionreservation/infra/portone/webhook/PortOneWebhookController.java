package com.example.attractionreservation.infra.portone.webhook;

import com.example.attractionreservation.domain.payment.service.WebhookEventCommandService;
import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.infra.portone.webhook.dto.PortOneWebhookEvent;
import io.portone.sdk.server.webhook.WebhookVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * PortOne 웹훅 수신. 서명 검증 후 저장만 하고 바로 200을 응답한다.
 * 결제 반영은 {@code WebhookEventProcessor}가 비동기로 처리하므로, 처리 결과와 무관하게 응답한다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PortOneWebhookController {
    private final PortOneWebhookVerifier portOneWebhookVerifier;
    private final WebhookEventCommandService webhookEventCommandService;

    @PostMapping("/api/payments/webhooks/portone")
    public ResponseEntity<ApiResponse<Void>> receiveWebhook(
            @RequestBody String body,  // 서명 검증을 위해 원문 그대로 받는다
            @RequestHeader(value = WebhookVerifier.HEADER_ID, required = false) String webhookId,
            @RequestHeader(value = WebhookVerifier.HEADER_SIGNATURE, required = false) String signature,
            @RequestHeader(value = WebhookVerifier.HEADER_TIMESTAMP, required = false) String timestamp
    ) {
        PortOneWebhookEvent event = portOneWebhookVerifier.verify(body, webhookId, signature, timestamp);

        boolean registered = webhookEventCommandService.register(webhookId, event.eventType(), event.pgId(), body);
        if (!registered) {
            log.info("[Webhook] 중복 수신: webhookId={}", webhookId);
        }

        return ResponseEntity.ok(ApiResponse.ok());
    }
}
