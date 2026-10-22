package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCompensationTest {

    private static final Instant NOW = Instant.parse("2026-10-20T01:00:00Z");
    private static final Duration STALE_TIMEOUT = Duration.ofMinutes(5);

    @Test
    @DisplayName("보상 요청은 REQUESTED로 생성되고 바로 처리 대상이 된다.")
    void request_valid_RequestedAndProcessable() {
        //when
        PaymentCompensation compensation = createCompensation();

        //then
        assertEquals(CompensationStatus.REQUESTED, compensation.getStatus());
        assertEquals(0, compensation.getAttemptCount());
        assertEquals(NOW, compensation.getNextRetryAt());
        assertNull(compensation.getProcessedAt());
        assertTrue(compensation.isProcessable(NOW, STALE_TIMEOUT));
    }

    @Test
    @DisplayName("요청 시각이 DB 반올림으로 now보다 미래로 저장돼도 REQUESTED는 바로 처리 대상이다.")
    void isProcessable_requestedWithFutureRequestTime_True() {
        //given
        PaymentCompensation compensation = PaymentCompensation.request("pg-id", 9999L, "결제 금액 불일치", NOW.plusMillis(500));

        //when & then
        assertTrue(compensation.isProcessable(NOW, STALE_TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("취소 금액이 0 이하면 보상 요청을 만들 수 없다.")
    void request_nonPositiveAmount_Fail(long amount) {
        //when & then
        assertThatThrownBy(() -> PaymentCompensation.request("pg-id", amount, "결제 금액 불일치", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());
    }

    @Test
    @DisplayName("취소 금액이 없으면 보상 요청을 만들 수 없다.")
    void request_nullAmount_Fail() {
        //when & then
        assertThatThrownBy(() -> PaymentCompensation.request("pg-id", null, "결제 금액 불일치", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());
    }

    @Test
    @DisplayName("처리를 시작하면 PROCESSING이 되고 시도 횟수가 1 증가한다.")
    void markAsProcessing_requested_Processing() {
        //given
        PaymentCompensation compensation = createCompensation();

        //when
        compensation.markAsProcessing();

        //then
        assertEquals(CompensationStatus.PROCESSING, compensation.getStatus());
        assertEquals(1, compensation.getAttemptCount());
    }

    @Test
    @DisplayName("재시도 대기 중인 보상은 다시 처리할 수 있다.")
    void markAsProcessing_failed_Processing() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.FAILED);

        //when
        compensation.markAsProcessing();

        //then
        assertEquals(CompensationStatus.PROCESSING, compensation.getStatus());
        assertEquals(2, compensation.getAttemptCount());
    }

    @ParameterizedTest
    @EnumSource(value = CompensationStatus.class, names = {"DONE", "DEAD"})
    @DisplayName("처리가 끝난 보상은 다시 처리할 수 없다.")
    void markAsProcessing_finished_Fail(CompensationStatus status) {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(status);

        //when & then
        assertThatThrownBy(compensation::markAsProcessing)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, compensation.getStatus());
    }

    @Test
    @DisplayName("PG 취소가 성공하면 DONE이 되고 처리 시각이 기록된다.")
    void markAsDone_processing_Done() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);

        //when
        compensation.markAsDone(NOW);

        //then
        assertEquals(CompensationStatus.DONE, compensation.getStatus());
        assertEquals(NOW, compensation.getProcessedAt());
        assertFalse(compensation.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("점유하지 않은 보상은 완료 처리할 수 없다.")
    void markAsDone_requested_Fail() {
        //given
        PaymentCompensation compensation = createCompensation();

        //when & then
        assertThatThrownBy(() -> compensation.markAsDone(NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(CompensationStatus.REQUESTED, compensation.getStatus());
    }

    @Test
    @DisplayName("첫 PG 취소에 실패하면 FAILED가 되고 1분 뒤 재시도로 예약된다.")
    void markAsFailed_firstAttempt_RetryAfterOneMinute() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);

        //when
        compensation.markAsFailed("결제 취소에 실패했습니다.", NOW);

        //then
        assertEquals(CompensationStatus.FAILED, compensation.getStatus());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), compensation.getNextRetryAt());
        assertEquals("결제 취소에 실패했습니다.", compensation.getLastError());
        assertFalse(compensation.isProcessable(NOW.plusSeconds(30), STALE_TIMEOUT));
        assertTrue(compensation.isProcessable(NOW.plus(Duration.ofMinutes(1)), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("재시도할수록 대기 시간이 두 배로 늘어난다.")
    void markAsFailed_thirdAttempt_RetryAfterFourMinutes() {
        //given
        PaymentCompensation compensation = createCompensation();
        for (int i = 0; i < 2; i++) {
            compensation.markAsProcessing();
            compensation.markAsFailed("error", NOW);
        }
        compensation.markAsProcessing();

        //when
        compensation.markAsFailed("error", NOW);

        //then
        assertEquals(3, compensation.getAttemptCount());
        assertEquals(NOW.plus(Duration.ofMinutes(4)), compensation.getNextRetryAt());
    }

    @Test
    @DisplayName("최대 시도 횟수만큼 실패하면 DEAD가 되고 처리 종료 시각이 기록되며 더 이상 처리 대상이 아니다.")
    void markAsFailed_maxAttempts_Dead() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.DEAD);

        //then
        assertEquals(CompensationStatus.DEAD, compensation.getStatus());
        assertEquals(PaymentCompensation.MAX_ATTEMPTS, compensation.getAttemptCount());
        assertEquals(NOW, compensation.getProcessedAt());
        assertFalse(compensation.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("실패 사유가 500자를 넘으면 500자로 잘라 저장한다.")
    void markAsFailed_longError_Truncated() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);

        //when
        compensation.markAsFailed("e".repeat(600), NOW);

        //then
        assertEquals(500, compensation.getLastError().length());
    }

    @Test
    @DisplayName("점유하지 않은 보상은 실패 처리할 수 없고 실패 사유도 기록되지 않는다.")
    void markAsFailed_requested_Fail() {
        //given
        PaymentCompensation compensation = createCompensation();

        //when & then
        assertThatThrownBy(() -> compensation.markAsFailed("error", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(CompensationStatus.REQUESTED, compensation.getStatus());
        assertNull(compensation.getLastError());
    }

    @Test
    @DisplayName("처리 중인 보상을 DEAD로 기록하면 사유와 처리 종료 시각이 남는다.")
    void markAsDead_processing_Dead() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);

        //when
        compensation.markAsDead("PG 결제 상태 확인 필요", NOW);

        //then
        assertEquals(CompensationStatus.DEAD, compensation.getStatus());
        assertEquals("PG 결제 상태 확인 필요", compensation.getLastError());
        assertEquals(NOW, compensation.getProcessedAt());
    }

    @Test
    @DisplayName("처리 중 상태로 멈춘 지 기준 시간이 지나면 다시 처리할 수 있다.")
    void isProcessable_staleProcessing_True() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);
        ReflectionTestUtils.setField(compensation, "updatedAt", NOW.minus(Duration.ofMinutes(10)));

        //when & then
        assertTrue(compensation.isProcessable(NOW, STALE_TIMEOUT));
    }

    @Test
    @DisplayName("처리 중 상태가 기준 시간 안이면 다른 처리가 진행 중이므로 처리할 수 없다.")
    void isProcessable_recentProcessing_False() {
        //given
        PaymentCompensation compensation = createCompensationWithStatus(CompensationStatus.PROCESSING);
        ReflectionTestUtils.setField(compensation, "updatedAt", NOW.minus(Duration.ofMinutes(1)));

        //when & then
        assertFalse(compensation.isProcessable(NOW, STALE_TIMEOUT));
    }

    private PaymentCompensation createCompensation() {
        return PaymentCompensation.request("pg-id", 9999L, "결제 금액 불일치", NOW);
    }

    private PaymentCompensation createCompensationWithStatus(CompensationStatus status) {
        PaymentCompensation compensation = createCompensation();
        switch (status) {
            case REQUESTED -> {
            }
            case PROCESSING -> compensation.markAsProcessing();
            case FAILED -> {
                compensation.markAsProcessing();
                compensation.markAsFailed("error", NOW);
            }
            case DONE -> {
                compensation.markAsProcessing();
                compensation.markAsDone(NOW);
            }
            case DEAD -> {
                for (int i = 0; i < PaymentCompensation.MAX_ATTEMPTS; i++) {
                    compensation.markAsProcessing();
                    compensation.markAsFailed("error", NOW);
                }
            }
        }
        return compensation;
    }
}
