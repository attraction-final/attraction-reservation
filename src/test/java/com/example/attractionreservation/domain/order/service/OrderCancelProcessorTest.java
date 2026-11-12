package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancelProcessorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private TicketProductService ticketProductService;

    @InjectMocks
    private OrderCancelProcessor processor;

    @Test
    @DisplayName("결제 잠금 후 주문을 수정하고 결제를 취소한다")
    void cancelExpiredOrder_pending_cancelsInOrder() {
        Instant now = Instant.now();

        Order order = createOrder();
        addItem(order, 10L, 2);
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.of(order));

        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(1);

        boolean result = processor.cancelExpiredOrder(101L, 1L, now);

        assertThat(result).isTrue();

        var inOrder = inOrder(paymentService, orderRepository, ticketProductService);

        inOrder.verify(paymentService)
                .lockPaymentByOrderIdIfExists(101L);
        inOrder.verify(orderRepository).cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        );
        // 주문 취소가 확정된 뒤에 재고를 복구하고, 그 다음 결제를 취소한다.
        inOrder.verify(ticketProductService)
                .updateTicketProductsStockIncrease(Map.of(10L, 2));
        inOrder.verify(paymentService)
                .cancelledByUser(1L, 101L);
    }

    @Test
    @DisplayName("주문 수정이 0건이면 결제 상태를 변경하지 않는다")
    void cancelExpiredOrder_orderChanged_doesNotCancelPayment() {
        Instant now = Instant.now();

        Order order = createOrder();
        addItem(order, 10L, 2);
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.of(order));

        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(0);

        boolean result = processor.cancelExpiredOrder(101L, 1L, now);

        assertThat(result).isFalse();

        verify(paymentService).lockPaymentByOrderIdIfExists(101L);
        verify(paymentService, never())
                .cancelledByUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("주문 수정이 0건이면 재고를 복구하지 않는다")
    void cancelExpiredOrder_orderChanged_doesNotRestoreStock() {
        // given: 사용자가 먼저 취소했거나 결제가 완료되어 조건부 수정이 0건이다.
        Instant now = Instant.now();

        Order order = createOrder();
        addItem(order, 10L, 2);
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(0);

        // when
        boolean result = processor.cancelExpiredOrder(101L, 1L, now);

        // then: 취소가 확정되지 않았으므로 이중 복구를 막기 위해 복구하지 않는다.
        assertThat(result).isFalse();
        verifyNoInteractions(ticketProductService);
    }

    @Test
    @DisplayName("같은 상품이 여러 항목이면 수량을 합산해 재고를 복구한다")
    void cancelExpiredOrder_sameProductInMultipleItems_restoresSummedStock() {
        // given
        Instant now = Instant.now();

        Order order = createOrder();
        addItem(order, 10L, 2);
        addItem(order, 10L, 3);
        addItem(order, 20L, 1);
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(1);

        // when
        processor.cancelExpiredOrder(101L, 1L, now);

        // then
        verify(ticketProductService)
                .updateTicketProductsStockIncrease(Map.of(10L, 5, 20L, 1));
    }

    @Test
    @DisplayName("주문이 존재하지 않으면 결제와 재고를 건드리지 않고 예외가 발생한다")
    void cancelExpiredOrder_missingOrder_throwsNotFound() {
        // given
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                processor.cancelExpiredOrder(101L, 1L, Instant.now()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );
        verifyNoInteractions(paymentService, ticketProductService);
    }

    @Test
    @DisplayName("결제 취소에 실패하면 예외를 전파해 재고 복구도 롤백되게 한다")
    void cancelExpiredOrder_paymentCancelFails_propagatesException() {
        // given
        Instant now = Instant.now();

        Order order = createOrder();
        addItem(order, 10L, 2);
        when(orderRepository.findOrderById(101L)).thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(1);
        doThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_PROHIBITED))
                .when(paymentService).cancelledByUser(1L, 101L);

        // when & then
        assertThatThrownBy(() -> processor.cancelExpiredOrder(101L, 1L, now))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PAYMENT_CANCEL_PROHIBITED)
                );
    }

    private Order createOrder() {
        User user = User.builder()
                .email("expired@example.com")
                .password("encoded-password")
                .nickname("만료회원")
                .build();
        ReflectionTestUtils.setField(user, "id", 1L);

        Order order = Order.create(user, 60000, LocalDate.of(2026, 11, 1));
        ReflectionTestUtils.setField(order, "id", 101L);
        return order;
    }

    private void addItem(Order order, Long productId, int quantity) {
        TicketProduct product = new TicketProduct(
                "테스트상품", 30000, "테스트설명",
                TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        ReflectionTestUtils.setField(product, "id", productId);
        order.addOrderItem(product, quantity);
    }
}
