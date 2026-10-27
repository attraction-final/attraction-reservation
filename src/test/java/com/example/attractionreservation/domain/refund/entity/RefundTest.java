package com.example.attractionreservation.domain.refund.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
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

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PROCESSING", "FAILED", "DEAD"})
    @DisplayName("PG에 보낸 적이 있는 환불은 PG 재조회로 취소가 확인되면 COMPLETED가 되고 완료 시각이 기록된다.")
    void markAsCompleteByPg_sentToPg_Completed(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);

        //when
        refund.markAsCompleteByPg(NOW);

        //then
        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(NOW, refund.getCompletedAt());
        assertFalse(refund.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "COMPLETED"})
    @DisplayName("PG에 보낸 적이 없거나 이미 완료된 환불은 PG 기준 완료 처리할 수 없다.")
    void markAsCompleteByPg_notSentOrCompleted_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);
        Instant completedAt = refund.getCompletedAt();

        //when & then
        assertThatThrownBy(() -> refund.markAsCompleteByPg(NOW.plusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
        assertEquals(completedAt, refund.getCompletedAt());
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

    @Test
    @DisplayName("처리 중인 환불을 확인 대기로 바꾸면 취소 내역 ID와 시작 시각을 기록하고 1분 뒤 첫 확인을 예약한다.")
    void markAsPgPending_processing_PgPending() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when
        refund.markAsPgPending("cancel-1", NOW);

        //then
        assertEquals(RefundStatus.PG_PENDING, refund.getStatus());
        assertEquals("cancel-1", refund.getPgCancellationId());
        assertEquals(NOW, refund.getPgPendingSince());
        assertEquals(NOW.plus(Duration.ofMinutes(1)), refund.getNextRetryAt());
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"REQUESTED", "FAILED", "COMPLETED", "DEAD"})
    @DisplayName("처리 중이 아닌 환불은 확인 대기로 바꿀 수 없다.")
    void markAsPgPending_notProcessing_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);

        //when & then
        assertThatThrownBy(() -> refund.markAsPgPending("cancel-1", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
    }

    @Test
    @DisplayName("확인 대기 환불은 취소를 다시 보내지 않도록 처리 대상이 아니고, 확인 시각부터 확인 대상이다.")
    void isPgCheckable_pgPending_FromNextCheck() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);
        Instant nextCheck = NOW.plus(Duration.ofMinutes(1));

        //when & then
        assertFalse(refund.isProcessable(NOW.plus(Duration.ofDays(1)), STALE_TIMEOUT));
        assertTrue(refund.isPgCheckable(nextCheck));
        assertFalse(refund.isPgCheckable(nextCheck.minusMillis(1)));
    }

    @Test
    @DisplayName("확인 대기 환불의 취소 완료를 확인하면 COMPLETED가 되고 완료 시각이 기록된다.")
    void confirmPgCancelled_pgPending_Completed() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when
        refund.confirmPgCancelled(NOW.plusSeconds(30));

        //then
        assertEquals(RefundStatus.COMPLETED, refund.getStatus());
        assertEquals(NOW.plusSeconds(30), refund.getCompletedAt());
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"PROCESSING", "FAILED", "DEAD"})
    @DisplayName("확인 대기가 아닌 환불은 조회 결과로 완료하지 않는다.")
    void confirmPgCancelled_notPgPending_Fail(RefundStatus status) {
        //given
        Refund refund = createRefundWithStatus(status);

        //when & then
        assertThatThrownBy(() -> refund.confirmPgCancelled(NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, refund.getStatus());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소 실패를 확인하면 FAILED가 되어 백오프 후 다시 처리할 수 있다.")
    void confirmPgCancelFailed_pgPending_Failed() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when
        refund.confirmPgCancelFailed("PG 취소 실패 확인", NOW);

        //then
        assertEquals(RefundStatus.FAILED, refund.getStatus());
        assertEquals("PG 취소 실패 확인", refund.getLastError());
        assertTrue(refund.isProcessable(NOW.plus(Duration.ofMinutes(1)), STALE_TIMEOUT));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "3, 3", "10, 10", "45, 30"})
    @DisplayName("다음 확인 간격은 확인 대기가 된 뒤 지난 시간이고 1분~30분 사이로 제한된다.")
    void reschedulePgCheck_beforeTimeout_RescheduledWithElapsed(long elapsedMinutes, long expectedDelayMinutes) {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);
        Instant now = NOW.plus(Duration.ofMinutes(elapsedMinutes));

        //when
        refund.reschedulePgCheck(now);

        //then
        assertEquals(RefundStatus.PG_PENDING, refund.getStatus());
        assertEquals(now.plus(Duration.ofMinutes(expectedDelayMinutes)), refund.getNextRetryAt());
    }

    @Test
    @DisplayName("확인 기한 직전이면 DEAD가 되지 않고 확인 대기를 유지한다.")
    void reschedulePgCheck_justBeforeTimeout_PgPending() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when
        refund.reschedulePgCheck(NOW.plus(Refund.PG_CHECK_TIMEOUT).minusMillis(1));

        //then
        assertEquals(RefundStatus.PG_PENDING, refund.getStatus());
    }

    @Test
    @DisplayName("확인 기한이 지나도 확정되지 않으면 DEAD가 되고 사유가 기록된다.")
    void reschedulePgCheck_afterTimeout_Dead() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when
        refund.reschedulePgCheck(NOW.plus(Refund.PG_CHECK_TIMEOUT));

        //then
        assertEquals(RefundStatus.DEAD, refund.getStatus());
        assertEquals("PG 취소 확인 기한 초과", refund.getLastError());
        assertFalse(refund.isPgCheckable(NOW.plus(Duration.ofDays(2))));
    }

    @Test
    @DisplayName("점유한 처리의 시도 횟수와 같으면 결과를 기록할 수 있다.")
    void verifyClaimedBy_sameAttempt_Pass() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);

        //when & then
        assertThatCode(() -> refund.verifyClaimedBy(1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("다른 처리가 다시 점유해 시도 횟수가 달라졌으면 이전 처리의 결과는 거부한다.")
    void verifyClaimedBy_reclaimedByOther_Fail() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PROCESSING);
        refund.markAsProcessing(); // 멈춘 환불로 보고 다시 점유

        //when & then
        assertThatThrownBy(() -> refund.verifyClaimedBy(1))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
    }

    @Test
    @DisplayName("같은 확인 대기 주기를 확인한 결과는 기록할 수 있다.")
    void verifyPgPendingSince_sameCycle_Pass() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when & then
        assertThatCode(() -> refund.verifyPgPendingSince(NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("확인 사이에 새 확인 대기 주기가 됐으면 이전 주기의 확인 결과는 거부한다.")
    void verifyPgPendingSince_otherCycle_Fail() {
        //given
        Refund refund = createRefundWithStatus(RefundStatus.PG_PENDING);

        //when & then
        assertThatThrownBy(() -> refund.verifyPgPendingSince(NOW.minusSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
    }

    @Test
    @DisplayName("환불 항목을 상품별 수량으로 합산하고, 같은 상품이 여러 항목이면 수량을 더한다.")
    void getQuantityByTicketProductId_sameProductInMultipleItems_SumsQuantity() {
        //given
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        TicketProduct sharedProduct = createTicketProduct(10L);
        TicketProduct otherProduct = createTicketProduct(20L);
        refund.addRefundItem(RefundItem.of(createOrderItem(order, 10000, 2, sharedProduct), 2, 2));
        refund.addRefundItem(RefundItem.of(createOrderItem(order, 10000, 3, sharedProduct), 3, 3));
        refund.addRefundItem(RefundItem.of(createOrderItem(order, 5000, 1, otherProduct), 1, 1));

        //when & then
        assertEquals(Map.of(10L, 5, 20L, 1), refund.getQuantityByTicketProductId());
    }

    private TicketProduct createTicketProduct(Long id) {
        TicketProduct product = new TicketProduct(
                "종일이용권", 10000, "설명", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    private OrderItem createOrderItem(Order owner, int price, int quantity, TicketProduct ticketProduct) {
        OrderItem orderItem = createOrderItem(owner, price, quantity);
        ReflectionTestUtils.setField(orderItem, "ticketProduct", ticketProduct);
        return orderItem;
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
            case PG_PENDING -> {
                refund.markAsProcessing();
                refund.markAsPgPending("cancel-1", NOW);
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
