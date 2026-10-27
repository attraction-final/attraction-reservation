package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancelProcessorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderCancelProcessor processor;

    @Test
    @DisplayName("결제 잠금 후 주문을 수정하고 결제를 취소한다")
    void cancelExpiredOrder_pending_cancelsInOrder() {
        Instant now = Instant.now();

        when(orderRepository.cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        )).thenReturn(1);

        boolean result = processor.cancelExpiredOrder(101L, 1L, now);

        assertThat(result).isTrue();

        var order = inOrder(paymentService, orderRepository);

        order.verify(paymentService)
                .lockPaymentByOrderIdIfExists(101L);
        order.verify(orderRepository).cancelPendingOrder(
                101L, 1L,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        );
        order.verify(paymentService)
                .cancelPendingPaymentIfExists(1L, 101L);
    }

    @Test
    @DisplayName("주문 수정이 0건이면 결제 상태를 변경하지 않는다")
    void cancelExpiredOrder_orderChanged_doesNotCancelPayment() {
        Instant now = Instant.now();

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
                .cancelPendingPaymentIfExists(anyLong(), anyLong());
    }
}