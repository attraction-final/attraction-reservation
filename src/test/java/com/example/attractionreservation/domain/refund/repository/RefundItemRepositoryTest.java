package com.example.attractionreservation.domain.refund.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.refund.dto.RefundedQuantity;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
class RefundItemRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");

    @Autowired
    private RefundItemRepository refundItemRepository;

    @Autowired
    private EntityManager entityManager;

    private Payment payment;
    private OrderItem adult;
    private OrderItem child;

    @BeforeEach
    void setUp() {
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
        entityManager.persist(order);

        payment = Payment.create(order);
        payment.markAsPaid(NOW);
        entityManager.persist(payment);
    }

    @Test
    @DisplayName("모든 상태를 넘기면 주문 상품별로 모든 환불 수량을 합산한다.")
    void sumQuantity_allStatuses_SumPerOrderItem() {
        //given
        saveRefund(RefundStatus.REQUESTED, adult, 1);
        saveRefund(RefundStatus.FAILED, adult, 1);
        saveRefund(RefundStatus.DEAD, adult, 1);
        saveRefund(RefundStatus.COMPLETED, child, 2);
        saveRefund(RefundStatus.PROCESSING, child, 1);
        flushAndClear();

        //when
        List<RefundedQuantity> result = refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(
                Set.of(adult.getId(), child.getId()), List.of(RefundStatus.values()));

        //then
        assertThat(result).containsExactlyInAnyOrder(
                new RefundedQuantity(adult.getId(), 3L),
                new RefundedQuantity(child.getId(), 3L));
    }

    @Test
    @DisplayName("넘긴 상태의 환불 수량만 합산하고, 해당 환불이 없는 주문 상품은 결과에 포함하지 않는다.")
    void sumQuantity_completedOnly_ExcludeOtherStatuses() {
        //given
        saveRefund(RefundStatus.COMPLETED, adult, 1);
        saveRefund(RefundStatus.FAILED, adult, 1);
        saveRefund(RefundStatus.REQUESTED, child, 2);
        flushAndClear();

        //when
        List<RefundedQuantity> result = refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(
                Set.of(adult.getId(), child.getId()), List.of(RefundStatus.COMPLETED));

        //then
        assertThat(result).containsExactly(new RefundedQuantity(adult.getId(), 1L));
    }

    @Test
    @DisplayName("요청한 주문 상품의 환불 수량만 조회한다.")
    void sumQuantity_requestedOrderItemsOnly() {
        //given
        saveRefund(RefundStatus.REQUESTED, adult, 1);
        saveRefund(RefundStatus.REQUESTED, child, 1);
        flushAndClear();

        //when
        List<RefundedQuantity> result = refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(
                Set.of(adult.getId()), List.of(RefundStatus.values()));

        //then
        assertThat(result).containsExactly(new RefundedQuantity(adult.getId(), 1L));
    }

    private void saveRefund(RefundStatus status, OrderItem orderItem, int quantity) {
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        refund.addRefundItem(RefundItem.of(orderItem, quantity, orderItem.getQuantity()));
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
                for (int i = 0; i < 5; i++) {
                    refund.markAsProcessing();
                    refund.markAsFailed("error", NOW);
                }
            }
        }
        entityManager.persist(refund);
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

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
