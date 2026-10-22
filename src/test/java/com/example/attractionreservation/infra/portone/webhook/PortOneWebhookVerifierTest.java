package com.example.attractionreservation.infra.portone.webhook;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.config.PortOneProperties;
import com.example.attractionreservation.infra.portone.webhook.dto.PortOneWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortOneWebhookVerifierTest {

    private static final byte[] SECRET_KEY = "test-webhook-secret".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(SECRET_KEY);
    private static final String WEBHOOK_ID = "msg_test";
    private static final String PG_ID = "pg-id";
    private static final String PAID_BODY = """
            {"type":"Transaction.Paid","timestamp":"2026-10-20T01:00:00Z","data":{"paymentId":"pg-id","storeId":"store-id","transactionId":"tx-id"}}""";

    private PortOneWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        PortOneProperties properties = new PortOneProperties();
        properties.setWebhookSecret(SECRET);
        verifier = new PortOneWebhookVerifier(properties);
    }

    @Test
    @DisplayName("서명이 유효한 결제 완료 웹훅이면 이벤트 타입과 pgId를 반환한다.")
    void verify_validTransactionWebhook_Success() {
        //given
        String timestamp = now();

        //when
        PortOneWebhookEvent event = verifier.verify(PAID_BODY, WEBHOOK_ID, sign(WEBHOOK_ID, timestamp, PAID_BODY), timestamp);

        //then
        assertThat(event.eventType()).isEqualTo("WebhookTransactionPaid");
        assertThat(event.pgId()).isEqualTo(PG_ID);
    }

    @Test
    @DisplayName("결제와 무관한 웹훅이면 pgId 없이 반환한다.")
    void verify_billingKeyWebhook_NoPgId() {
        //given
        String body = """
                {"type":"BillingKey.Issued","timestamp":"2026-10-20T01:00:00Z","data":{"billingKey":"billing-key","storeId":"store-id"}}""";
        String timestamp = now();

        //when
        PortOneWebhookEvent event = verifier.verify(body, WEBHOOK_ID, sign(WEBHOOK_ID, timestamp, body), timestamp);

        //then
        assertThat(event.eventType()).isEqualTo("WebhookBillingKeyIssued");
        assertThat(event.pgId()).isNull();
    }

    @Test
    @DisplayName("SDK가 알지 못하는 웹훅 타입이면 Unrecognized로 pgId 없이 반환한다.")
    void verify_unknownType_Unrecognized() {
        //given
        String body = """
                {"type":"Unknown.Event","timestamp":"2026-10-20T01:00:00Z","data":{}}""";
        String timestamp = now();

        //when
        PortOneWebhookEvent event = verifier.verify(body, WEBHOOK_ID, sign(WEBHOOK_ID, timestamp, body), timestamp);

        //then
        assertThat(event.eventType()).isEqualTo("Unrecognized");
        assertThat(event.pgId()).isNull();
    }

    @Test
    @DisplayName("서명이 다르면 웹훅 서명 오류가 발생한다.")
    void verify_invalidSignature_Fail() {
        //given
        String timestamp = now();
        String signature = sign(WEBHOOK_ID, timestamp, PAID_BODY.replace("pg-id", "other-pg-id"));

        //when & then
        assertThatThrownBy(() -> verifier.verify(PAID_BODY, WEBHOOK_ID, signature, timestamp))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE.getMessage());
    }

    @Test
    @DisplayName("타임스탬프가 5분보다 오래됐으면 웹훅 서명 오류가 발생한다.")
    void verify_oldTimestamp_Fail() {
        //given
        String timestamp = String.valueOf(Instant.now().minusSeconds(600).getEpochSecond());

        //when & then
        assertThatThrownBy(() -> verifier.verify(PAID_BODY, WEBHOOK_ID, sign(WEBHOOK_ID, timestamp, PAID_BODY), timestamp))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE.getMessage());
    }

    @Test
    @DisplayName("서명 헤더가 없으면 웹훅 서명 오류가 발생한다.")
    void verify_missingHeader_Fail() {
        //when & then
        assertThatThrownBy(() -> verifier.verify(PAID_BODY, WEBHOOK_ID, null, now()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE.getMessage());
    }

    private String now() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    // Standard Webhooks: base64(HMAC-SHA256(secret, "{id}.{timestamp}.{body}")), "v1," 접두사
    private String sign(String webhookId, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET_KEY, "HmacSHA256"));
            byte[] signature = mac.doFinal((webhookId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
