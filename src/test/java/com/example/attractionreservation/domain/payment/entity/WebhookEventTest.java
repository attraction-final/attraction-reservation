package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookEventTest {

    private static final Instant NOW = Instant.parse("2026-10-20T01:00:00Z");

    @Test
    @DisplayName("pgId가 있는 웹훅은 처리 대기 상태로 생성된다.")
    void receive_withPgId_Received() {
        //when
        WebhookEvent event = createEvent();

        //then
        assertEquals(WebhookStatus.RECEIVED, event.getStatus());
        assertEquals(0, event.getAttemptCount());
    }

    @Test
    @DisplayName("pgId가 없는 웹훅은 처리 대상이 아니므로 IGNORED로 생성된다.")
    void receive_withoutPgId_Ignored() {
        //when
        WebhookEvent event = WebhookEvent.receive("webhook-id", null, "WebhookBillingKeyIssued", "{}");

        //then
        assertEquals(WebhookStatus.IGNORED, event.getStatus());
    }

    @Test
    @DisplayName("처리를 시작하면 PROCESSING이 되고 시도 횟수가 1 증가한다.")
    void markAsProcessing_received_Processing() {
        //given
        WebhookEvent event = createEvent();

        //when
        event.markAsProcessing();

        //then
        assertEquals(WebhookStatus.PROCESSING, event.getStatus());
        assertEquals(1, event.getAttemptCount());
    }

    @Test
    @DisplayName("처리 중 멈춘 웹훅은 다시 점유할 수 있고 시도 횟수가 증가한다.")
    void markAsProcessing_processing_Reclaimed() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsProcessing();

        //then
        assertEquals(WebhookStatus.PROCESSING, event.getStatus());
        assertEquals(2, event.getAttemptCount());
    }

    @Test
    @DisplayName("재시도 대기 중인 웹훅은 다시 처리할 수 있다.")
    void markAsProcessing_failed_Processing() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();
        event.markAsFailed("error", NOW);

        //when
        event.markAsProcessing();

        //then
        assertEquals(WebhookStatus.PROCESSING, event.getStatus());
        assertEquals(2, event.getAttemptCount());
    }

    @ParameterizedTest
    @EnumSource(value = WebhookStatus.class, names = {"PROCESSED", "IGNORED", "DEAD"})
    @DisplayName("처리가 끝난 웹훅은 다시 처리할 수 없다.")
    void markAsProcessing_finished_Fail(WebhookStatus status) {
        //given
        WebhookEvent event = createEventWithStatus(status);

        //when & then
        assertThatThrownBy(event::markAsProcessing)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, event.getStatus());
    }

    @Test
    @DisplayName("처리 중인 웹훅을 완료 처리하면 PROCESSED가 되고 처리 시각이 기록된다.")
    void markAsProcessed_processing_Success() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsProcessed(NOW);

        //then
        assertEquals(WebhookStatus.PROCESSED, event.getStatus());
        assertEquals(NOW, event.getProcessedAt());
    }

    @Test
    @DisplayName("점유하지 않은 웹훅은 완료 처리할 수 없다.")
    void markAsProcessed_received_Fail() {
        //given
        WebhookEvent event = createEvent();

        //when & then
        assertThatThrownBy(() -> event.markAsProcessed(NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(WebhookStatus.RECEIVED, event.getStatus());
    }

    @Test
    @DisplayName("처리 중 우리 결제가 아님을 확인하면 IGNORED가 된다.")
    void markAsIgnored_processing_Success() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsIgnored(NOW);

        //then
        assertEquals(WebhookStatus.IGNORED, event.getStatus());
        assertEquals(NOW, event.getProcessedAt());
    }

    @Test
    @DisplayName("첫 처리에 실패하면 FAILED가 되고 1분 뒤 재시도로 예약된다.")
    void markAsFailed_firstAttempt_RetryAfterOneMinute() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsFailed("PG 조회 실패", NOW);

        //then
        assertEquals(WebhookStatus.FAILED, event.getStatus());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), event.getNextRetryAt());
        assertEquals("PG 조회 실패", event.getLastError());
        assertNull(event.getProcessedAt());
    }

    @Test
    @DisplayName("재시도할수록 대기 시간이 두 배로 늘어난다.")
    void markAsFailed_thirdAttempt_RetryAfterFourMinutes() {
        //given
        WebhookEvent event = createEvent();
        for (int i = 0; i < 2; i++) {
            event.markAsProcessing();
            event.markAsFailed("error", NOW);
        }
        event.markAsProcessing();

        //when
        event.markAsFailed("error", NOW);

        //then
        assertEquals(3, event.getAttemptCount());
        assertEquals(NOW.plus(Duration.ofMinutes(4)), event.getNextRetryAt());
    }

    @Test
    @DisplayName("최대 시도 횟수만큼 실패하면 DEAD가 되고 더 이상 재시도하지 않으며 처리 종료 시각이 기록된다.")
    void markAsFailed_maxAttempts_Dead() {
        //given
        WebhookEvent event = createEventWithStatus(WebhookStatus.DEAD);

        //then
        assertEquals(WebhookStatus.DEAD, event.getStatus());
        assertEquals(WebhookEvent.MAX_ATTEMPTS, event.getAttemptCount());
        assertNull(event.getNextRetryAt());
        assertEquals(NOW, event.getProcessedAt());
    }

    @Test
    @DisplayName("실패 사유가 500자를 넘으면 500자로 잘라 저장한다.")
    void markAsFailed_longError_Truncated() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsFailed("e".repeat(600), NOW);

        //then
        assertEquals(500, event.getLastError().length());
    }

    @Test
    @DisplayName("처리 중인 웹훅을 DEAD로 기록하면 재시도하지 않고 사유와 처리 종료 시각이 남는다.")
    void markAsDead_processing_Dead() {
        //given
        WebhookEvent event = createEvent();
        event.markAsProcessing();

        //when
        event.markAsDead("PG 보상 취소 실패: 수동 환불 필요", NOW);

        //then
        assertEquals(WebhookStatus.DEAD, event.getStatus());
        assertEquals("PG 보상 취소 실패: 수동 환불 필요", event.getLastError());
        assertNull(event.getNextRetryAt());
        assertEquals(NOW, event.getProcessedAt());
        assertFalse(event.isProcessable(NOW.plus(Duration.ofDays(1)), Duration.ofMinutes(5)));
    }

    @ParameterizedTest
    @EnumSource(value = WebhookStatus.class, names = {"RECEIVED", "FAILED", "PROCESSED", "IGNORED", "DEAD"})
    @DisplayName("처리 중이 아닌 웹훅은 DEAD로 기록할 수 없다.")
    void markAsDead_notProcessing_Fail(WebhookStatus status) {
        //given
        WebhookEvent event = createEventWithStatus(status);

        //when & then
        assertThatThrownBy(() -> event.markAsDead("reason", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, event.getStatus());
    }

    @Test
    @DisplayName("점유하지 않은 웹훅은 실패 처리할 수 없고 실패 사유도 기록되지 않는다.")
    void markAsFailed_received_Fail() {
        //given
        WebhookEvent event = createEvent();

        //when & then
        assertThatThrownBy(() -> event.markAsFailed("error", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(WebhookStatus.RECEIVED, event.getStatus());
        assertNull(event.getLastError());
    }

    @Test
    @DisplayName("처리 중 상태로 멈춘 지 기준 시간이 지나면 다시 처리할 수 있다.")
    void isProcessable_staleProcessing_True() {
        //given
        WebhookEvent event = createEventWithStatus(WebhookStatus.PROCESSING);
        ReflectionTestUtils.setField(event, "updatedAt", NOW.minus(Duration.ofMinutes(10)));

        //when & then
        assertTrue(event.isProcessable(NOW, Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("처리 중 상태가 기준 시간 안이면 다른 처리가 진행 중이므로 처리할 수 없다.")
    void isProcessable_recentProcessing_False() {
        //given
        WebhookEvent event = createEventWithStatus(WebhookStatus.PROCESSING);
        ReflectionTestUtils.setField(event, "updatedAt", NOW.minus(Duration.ofMinutes(1)));

        //when & then
        assertFalse(event.isProcessable(NOW, Duration.ofMinutes(5)));
    }

    @ParameterizedTest
    @EnumSource(value = WebhookStatus.class, names = {"PROCESSED", "IGNORED", "DEAD"})
    @DisplayName("처리가 끝난 웹훅은 처리 대상이 아니다.")
    void isProcessable_finished_False(WebhookStatus status) {
        //given
        WebhookEvent event = createEventWithStatus(status);

        //when & then
        assertFalse(event.isProcessable(NOW.plus(Duration.ofDays(1)), Duration.ofMinutes(5)));
    }

    private WebhookEvent createEvent() {
        return WebhookEvent.receive("webhook-id", "pg-id", "WebhookTransactionPaid", "{}");
    }

    private WebhookEvent createEventWithStatus(WebhookStatus status) {
        WebhookEvent event = createEvent();
        switch (status) {
            case RECEIVED -> {
            }
            case PROCESSING -> event.markAsProcessing();
            case FAILED -> {
                event.markAsProcessing();
                event.markAsFailed("error", NOW);
            }
            case PROCESSED -> {
                event.markAsProcessing();
                event.markAsProcessed(NOW);
            }
            case IGNORED -> {
                event.markAsProcessing();
                event.markAsIgnored(NOW);
            }
            case DEAD -> {
                for (int i = 0; i < WebhookEvent.MAX_ATTEMPTS; i++) {
                    event.markAsProcessing();
                    event.markAsFailed("error", NOW);
                }
            }
        }
        return event;
    }
}
