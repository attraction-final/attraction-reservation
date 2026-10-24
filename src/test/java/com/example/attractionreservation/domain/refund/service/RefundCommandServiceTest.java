package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.refund.dto.ClaimedRefund;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, RefundCommandService.class})
class RefundCommandServiceTest {

    @Autowired
    private RefundCommandService refundCommandService;

    @Autowired
    private EntityManager entityManager;

    private Instant now;
    private Payment payment;
    private OrderItem adult;
    private OrderItem child;

    // 성인 10,000원 × 3장, 어린이 5,000원 × 4장 = 50,000원 결제
    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        entityManager.persist(user);

        TicketProduct ticketProduct = new TicketProduct(
                "종일이용권", 10000, "하루 동안 이용 가능한 입장권", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
        entityManager.persist(ticketProduct);

        Order order = Order.create(user, 50000);
        adult = addOrderItem(order, ticketProduct, 10000, 3);
        child = addOrderItem(order, ticketProduct, 5000, 4);
        order.markAsPaid();
        entityManager.persist(order);

        payment = Payment.create(order);
        payment.markAsPaid(now);
        entityManager.persist(payment);
    }

    @Test
    @DisplayName("환불을 점유하면 PROCESSING이 되고, PG 취소 가능 금액은 결제 금액에서 완료된 환불 금액만 뺀 값이다.")
    void claim_requested_ClaimedWithCancellableAmount() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);   // PG에 반영된 10,000원
        saveRefund(RefundStatus.FAILED, child, 1);      // 아직 PG에 반영되지 않은 5,000원
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 2);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now);

        //then
        assertThat(claimed).contains(new ClaimedRefund(refundId, payment.getPgId(), 20000L, "단순 변심", 40000L));
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 점유된 환불은 다시 점유하지 않는다.")
    void claim_processing_NotClaimed() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now);

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(refundId).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 결제의 다른 환불이 PG 취소 중이면 점유하지 않고 상태도 바꾸지 않는다.")
    void claim_otherRefundProcessing_NotClaimed() {
        //given
        Long inFlight = saveRefund(RefundStatus.REQUESTED, adult, 1);
        Long waiting = saveRefund(RefundStatus.REQUESTED, child, 1);
        refundCommandService.claim(inFlight, now);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(waiting, now);

        //then
        assertThat(claimed).isEmpty();
        Refund found = find(waiting);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(found.getAttemptCount()).isZero();
    }

    @Test
    @DisplayName("완료된 환불은 점유하지 않는다.")
    void claim_completed_NotClaimed() {
        //given
        Long refundId = saveRefund(RefundStatus.COMPLETED, adult, 1);
        flushAndClear();

        //when
        Optional<ClaimedRefund> claimed = refundCommandService.claim(refundId, now.plus(Duration.ofDays(1)));

        //then
        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("일부 상품의 환불을 완료하면 환불은 COMPLETED, 결제와 주문은 PARTIALLY_REFUNDED가 된다.")
    void recordComplete_partOfItems_PartiallyRefunded() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, now);
        flushAndClear();

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(found.getCompletedAt()).isEqualTo(now);
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("이번 환불로 모든 상품이 환불되면 결제와 주문은 REFUNDED가 된다.")
    void recordComplete_lastItems_Refunded() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 3);
        saveRefund(RefundStatus.COMPLETED, child, 1);
        Long refundId = saveRefund(RefundStatus.REQUESTED, child, 3);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, now);
        flushAndClear();

        //then
        Payment foundPayment = findPayment();
        assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(foundPayment.getOrder().getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    @DisplayName("다른 환불이 아직 재시도 대기 중이면 이번 환불이 완료돼도 PARTIALLY_REFUNDED다.")
    void recordComplete_otherRefundFailed_PartiallyRefunded() {
        //given
        saveRefund(RefundStatus.FAILED, adult, 3);
        Long refundId = saveRefund(RefundStatus.REQUESTED, child, 4);
        refundCommandService.claim(refundId, now);
        flushAndClear();

        //when
        refundCommandService.recordComplete(refundId, now);
        flushAndClear();

        //then
        assertThat(findPayment().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("PG 취소 실패를 기록하면 FAILED가 되고, 최대 시도 횟수를 넘으면 DEAD를 반환한다.")
    void recordFailure_untilMaxAttempts_FailedThenDead() {
        //given
        Long refundId = saveRefund(RefundStatus.REQUESTED, adult, 1);
        RefundStatus firstStatus = null;
        RefundStatus lastStatus = null;

        //when
        for (int i = 0; i < 5; i++) {
            Instant attemptAt = now.plus(Duration.ofHours(i));
            refundCommandService.claim(refundId, attemptAt);
            lastStatus = refundCommandService.recordFailure(refundId, "결제 취소에 실패했습니다.", attemptAt);
            if (i == 0) {
                firstStatus = lastStatus;
            }
        }

        //then
        assertThat(firstStatus).isEqualTo(RefundStatus.FAILED);
        assertThat(lastStatus).isEqualTo(RefundStatus.DEAD);
        assertThat(find(refundId).getLastError()).isEqualTo("결제 취소에 실패했습니다.");
    }

    @Test
    @DisplayName("재시도하면 안 되는 실패를 기록하면 시도 횟수와 관계없이 바로 DEAD가 된다.")
    void recordDead_processing_DeadWithoutRetry() {
        //given
        Long refundId = saveRefund(RefundStatus.PROCESSING, adult, 1);

        //when
        refundCommandService.recordDead(refundId, "이미 취소된 결제입니다.");

        //then
        Refund found = find(refundId);
        assertThat(found.getStatus()).isEqualTo(RefundStatus.DEAD);
        assertThat(found.getLastError()).isEqualTo("이미 취소된 결제입니다.");
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 완료된 환불은 DEAD로 기록하지 않는다.")
    void recordDead_completed_Fail() {
        //given
        Long refundId = saveRefund(RefundStatus.COMPLETED, adult, 1);

        //when & then
        assertThatThrownBy(() -> refundCommandService.recordDead(refundId, "이미 취소된 결제입니다."))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_STATUS_TRANSITION.getMessage());
        assertThat(find(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("처리 대상으로 요청된 환불과 재시도 시각이 지난 실패만 조회한다.")
    void findProcessableIds_mixedStatuses_OnlyProcessable() {
        //given
        Long requested = saveRefund(RefundStatus.REQUESTED, adult, 1);
        Long retryDue = saveRefund(RefundStatus.FAILED, adult, 1, now.minus(Duration.ofMinutes(10)));
        saveRefund(RefundStatus.FAILED, child, 1, now);            // 1분 뒤 재시도
        saveRefund(RefundStatus.COMPLETED, child, 1);
        saveRefund(RefundStatus.PROCESSING, child, 1);             // 방금 점유됨
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(requested, retryDue);
    }

    @Test
    @DisplayName("처리 중으로 멈춘 시각이 기준 시간과 같으면 처리 대상으로 조회된다.")
    void findProcessableIds_staleProcessingAtBoundary_Included() {
        //given
        Long stale = saveRefund(RefundStatus.PROCESSING, adult, 1);
        flushAndClear();
        entityManager.createQuery("update Refund r set r.updatedAt = :updatedAt where r.id = :id")
                .setParameter("updatedAt", now.minus(RefundCommandService.STALE_PROCESSING_TIMEOUT))
                .setParameter("id", stale)
                .executeUpdate();
        flushAndClear();

        //when
        List<Long> ids = refundCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(stale);
    }

    private Long saveRefund(RefundStatus status, OrderItem orderItem, int quantity) {
        return saveRefund(status, orderItem, quantity, now);
    }

    private Long saveRefund(RefundStatus status, OrderItem orderItem, int quantity, Instant at) {
        Refund refund = Refund.create(payment, "단순 변심", at);
        refund.addRefundItem(RefundItem.of(orderItem, quantity, orderItem.getQuantity()));
        switch (status) {
            case REQUESTED -> {
            }
            case PROCESSING -> refund.markAsProcessing();
            case FAILED -> {
                refund.markAsProcessing();
                refund.markAsFailed("error", at);
            }
            case COMPLETED -> {
                refund.markAsProcessing();
                refund.markAsComplete(at);
            }
            default -> throw new IllegalArgumentException("지원하지 않는 상태: " + status);
        }
        entityManager.persist(refund);
        entityManager.flush();
        return refund.getId();
    }

    // 주문 생성 기능이 없어 주문 상품을 직접 만든다. 주문을 저장하면 cascade로 함께 저장된다.
    private OrderItem addOrderItem(Order order, TicketProduct ticketProduct, int price, int quantity) {
        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "ticketProduct", ticketProduct);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        order.getOrderItems().add(orderItem);
        return orderItem;
    }

    private Refund find(Long refundId) {
        flushAndClear();
        return entityManager.find(Refund.class, refundId);
    }

    private Payment findPayment() {
        flushAndClear();
        return entityManager.find(Payment.class, payment.getId());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
