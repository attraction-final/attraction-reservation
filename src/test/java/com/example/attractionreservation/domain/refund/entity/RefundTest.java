package com.example.attractionreservation.domain.refund.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundTest {

    private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
    private static final Duration STALE_TIMEOUT = Duration.ofMinutes(5);

    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        order = createOrder(1L, 25000);
        payment = Payment.create(order);
    }

    @Test
    @DisplayName("환불을 생성하면 REQUESTED 상태이고 환불 금액·시도 횟수는 0이며 바로 처리 대상이다.")
    void create_Requested() {
        //when
        Refund refund = Refund.create(payment, "단순 변심", NOW);

        //then
        assertEquals(RefundStatus.REQUESTED, refund.getStatus());
        assertEquals(0, refund.getRefundAmount());
        assertEquals(0, refund.getAttemptCount());
        assertEquals(NOW, refund.getRequestedAt());
        assertTrue(refund.getRefundItems().isEmpty());
        assertTrue(refund.isProcessable(NOW.minusSeconds(1), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("같은 주문의 상품을 추가하면 환불 금액이 합산되고 환불 상품이 환불에 연결된다.")
    void addRefundItem_sameOrder_SumRefundAmount() {
        //given
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        RefundItem adult = RefundItem.of(createOrderItem(order, 10000, 2), 2, 2);
        RefundItem child = RefundItem.of(createOrderItem(order, 5000, 1), 1, 1);

        //when
        refund.addRefundItem(adult);
        refund.addRefundItem(child);

        //then
        assertEquals(25000, refund.getRefundAmount());
        assertEquals(2, refund.getRefundItems().size());
        assertSame(refund, adult.getRefund());
        assertSame(refund, child.getRefund());
    }

    @Test
    @DisplayName("다른 주문의 상품은 환불에 추가할 수 없다.")
    void addRefundItem_otherOrder_Fail() {
        //given
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        Order otherOrder = createOrder(2L, 10000);
        RefundItem otherItem = RefundItem.of(createOrderItem(otherOrder, 10000, 1), 1, 1);

        //when & then
        assertThatThrownBy(() -> refund.addRefundItem(otherItem))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_ORDER_ITEM.getMessage());
        assertEquals(0, refund.getRefundAmount());
        assertTrue(refund.getRefundItems().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "FAILED", "PROCESSING"})
    @DisplayName("요청·재시도 대기·처리 중인 환불은 점유하면 PROCESSING이 되고 시도 횟수가 1 증가한다.")
    void markAsProcessing_claimable_Processing(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);
        int attemptCount = refund.getAttemptCount();

        //when
        refund.markAsProcessing();

        //then
        assertEquals(RefundStatus.PROCESSING, refund.getStatus());
        assertEquals(attemptCount + 1, refund.getAttemptCount());
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"COMPLETED", "DEAD"})
    @DisplayName("끝난 환불은 다시 점유할 수 없다.")
    void markAsProcessing_finished_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);

        //when & then
        assertThatThrownBy(refund::markAsProcessing)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
    }

    @Test
    @DisplayName("처리 중인 환불을 완료 처리하면 COMPLETED가 되고 완료 시각이 기록되며 더 이상 처리 대상이 아니다.")
    void markAsComplete_processing_Completed() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when
        refund.markAsComplete(NOW.plusSeconds(10));

        //then
        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(NOW.plusSeconds(10), refund.getCompletedAt());
        assertFalse(refund.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "FAILED", "COMPLETED", "DEAD"})
    @DisplayName("처리 중이 아닌 환불은 완료 처리할 수 없다.")
    void markAsComplete_notProcessing_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);

        //when & then
        assertThatThrownBy(() -> refund.markAsComplete(NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
    }

    @Test
    @DisplayName("첫 PG 취소에 실패하면 FAILED가 되고 1분 뒤 재시도로 예약되며 완료 시각은 기록되지 않는다.")
    void markAsFailed_firstAttempt_RetryAfterOneMinute() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when
        refund.markAsFailed("결제 취소에 실패했습니다.", NOW);

        //then
        assertEquals(RefundStatus.FAILED, refund.getStatus());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), refund.getNextRetryAt());
        assertEquals("결제 취소에 실패했습니다.", refund.getLastError());
        assertNull(refund.getCompletedAt());
        assertFalse(refund.isProcessable(NOW.plusSeconds(30), STALE_TIMEOUT));
        assertTrue(refund.isProcessable(NOW.plus(Duration.ofMinutes(1)), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("재시도할수록 대기 시간이 두 배로 늘어난다.")
    void markAsFailed_thirdAttempt_RetryAfterFourMinutes() {
        //given
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        for (int i = 0; i < 2; i++) {
            refund.markAsProcessing();
            refund.markAsFailed("error", NOW);
        }
        refund.markAsProcessing();

        //when
        refund.markAsFailed("error", NOW);

        //then
        assertEquals(RefundStatus.FAILED, refund.getStatus());
        assertEquals(NOW.plus(Duration.ofMinutes(4)), refund.getNextRetryAt());
    }

    @Test
    @DisplayName("최대 시도 횟수만큼 실패하면 DEAD가 되고 더 이상 처리 대상이 아니다.")
    void markAsFailed_maxAttempts_Dead() {
        //when
        Refund refund = createRefundWithStatus(RefundStatus.DEAD);

        //then
        assertEquals(RefundStatus.DEAD, refund.getStatus());
        assertEquals(Refund.MAX_ATTEMPTS, refund.getAttemptCount());
        assertFalse(refund.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @Test
    @DisplayName("실패 사유가 500자를 넘으면 500자로 잘라 저장한다.")
    void markAsFailed_longError_Truncated() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when
        refund.markAsFailed("e".repeat(600), NOW);

        //then
        assertEquals(500, refund.getLastError().length());
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "FAILED", "COMPLETED", "DEAD"})
    @DisplayName("처리 중이 아닌 환불은 실패 처리할 수 없고 실패 사유도 기록되지 않는다.")
    void markAsFailed_notProcessing_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);
        String lastError = refund.getLastError();

        //when & then
        assertThatThrownBy(() -> refund.markAsFailed("new error", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
        assertEquals(lastError, refund.getLastError());
    }

    @Test
    @DisplayName("처리 중인 환불을 DEAD 처리하면 시도 횟수와 관계없이 DEAD가 되고 더 이상 처리 대상이 아니다.")
    void markAsDead_processing_Dead() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when
        refund.markAsDead("결제의 취소 가능 금액이 일치하지 않습니다.");

        //then
        assertEquals(RefundStatus.DEAD, refund.getStatus());
        assertEquals(1, refund.getAttemptCount());
        assertEquals("결제의 취소 가능 금액이 일치하지 않습니다.", refund.getLastError());
        assertNull(refund.getCompletedAt());
        assertFalse(refund.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "FAILED", "COMPLETED", "DEAD"})
    @DisplayName("처리 중이 아닌 환불은 DEAD 처리할 수 없고 실패 사유도 기록되지 않는다.")
    void markAsDead_notProcessing_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);
        String lastError = refund.getLastError();

        //when & then
        assertThatThrownBy(() -> refund.markAsDead("new error"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
        assertEquals(lastError, refund.getLastError());
    }

    @Test
    @DisplayName("처리 중으로 멈춘 시각이 기준 시간과 같거나 이전이면 다시 처리할 수 있고, 기준 시간 안이면 처리할 수 없다.")
    void isProcessable_processing_StaleOnly() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);
        ReflectionTestUtils.setField(refund, "updatedAt", NOW);

        //when & then
        assertTrue(refund.isProcessable(NOW.plus(STALE_TIMEOUT), STALE_TIMEOUT));
        assertFalse(refund.isProcessable(NOW.plus(STALE_TIMEOUT).minusMillis(1), STALE_TIMEOUT));
    }

    private Refund createRefundWithStatus(RefundStatus status) {
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        switch (status) {
            case REQUESTED -> {
            }
            case PROCESSING -> refund.markAsProcessing();
            case FAILED -> {
                refund.markAsProcessing();
                refund.markAsFailed("error", NOW);
            }
            case COMPLETED -> {
                refund.markAsProcessing();
                refund.markAsComplete(NOW);
            }
            case DEAD -> {
                for (int i = 0; i < Refund.MAX_ATTEMPTS; i++) {
                    refund.markAsProcessing();
                    refund.markAsFailed("error", NOW);
                }
            }
        }
        return refund;
    }

    private Order createOrder(Long id, int totalAmount) {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        Order created = Order.create(user, totalAmount);
        ReflectionTestUtils.setField(created, "id", id);
        return created;
    }

    private OrderItem createOrderItem(Order owner, int price, int quantity) {
        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", owner);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        return orderItem;
    }
}
