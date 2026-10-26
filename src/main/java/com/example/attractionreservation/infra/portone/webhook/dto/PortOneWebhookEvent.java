package com.example.attractionreservation.infra.portone.webhook.dto;

/**
 * 서명 검증을 통과한 PortOne 웹훅에서 꺼낸 값.
 *
 * @param eventType SDK 웹훅 타입 이름 (예: WebhookTransactionPaid, 알 수 없는 타입은 Unrecognized)
 * @param pgId      거래 웹훅의 paymentId. 빌링키 등 결제와 무관한 웹훅이면 null
 */
public record PortOneWebhookEvent(
        String eventType,
        String pgId
) {
}
