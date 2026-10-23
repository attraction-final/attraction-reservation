package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;


import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCancelServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TicketProductRepository ticketProductRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("본인의 결제 대기 주문을 취소한다")
    void updateOrderByCancel_ownedPendingOrder_success() {
        // given
        Order order = createOrder(1L);

        when(orderRepository.findById(101L))
                .thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(1);

        // when
        UpdateOrderResponse response =
                orderService.updateOrderByCancel(1L, 101L);

        // then
        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        verify(orderRepository).cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        );
        verifyNoInteractions(ticketProductRepository, userRepository);
    }

    @Test
    @DisplayName("존재하지 않는 주문은 취소할 수 없다")
    void updateOrderByCancel_missingOrder_throwsNotFound() {
        // given
        when(orderRepository.findById(101L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );

        // 조회에서 거부된 경우 수정 쿼리는 실행하지 않는다.
        verify(orderRepository).findById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("다른 회원의 주문은 취소할 수 없다")
    void updateOrderByCancel_otherUserOrder_throwsForbidden() {
        // given
        Order order = createOrder(2L);

        when(orderRepository.findById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_CANCEL_ACCESS_DENIED)
                );

        verify(orderRepository).findById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = "PENDING",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("결제 대기 상태가 아닌 주문은 취소할 수 없다")
    void updateOrderByCancel_nonPendingOrder_throwsConflict(
            OrderStatus status
    ) {
        // given
        Order order = createOrder(1L);
        ReflectionTestUtils.setField(order, "status", status);

        when(orderRepository.findById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );

        // 이미 취소됐거나 완료된 주문에는 수정 쿼리를 실행하지 않는다.
        verify(orderRepository).findById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("조회 후 다른 요청이 먼저 처리했다면 취소 실패로 응답한다")
    void updateOrderByCancel_updateConditionNoLongerMatches_throwsConflict() {
        // given
        Order order = createOrder(1L);

        when(orderRepository.findById(101L))
                .thenReturn(Optional.of(order));

        // 조회 당시에는 PENDING이지만 실제 수정 시에는 조건이 맞지 않는 상황을 재현한다.
        when(orderRepository.cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(0);

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );
    }

    @Test
    @DisplayName("현재 시각의 30분 전을 기준으로 자동 취소하고 처리 건수를 반환한다")
    void updateExpiredOrdersByCancel_calculatesThirtyMinuteCutoff_returnsCount() {
        // given
        when(orderRepository.cancelExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(2);

        Instant beforeCall = Instant.now();

        // when
        int cancelledCount = orderService.updateExpiredOrdersByCancel();

        Instant afterCall = Instant.now();

        // then
        ArgumentCaptor<Instant> cutoffCaptor =
                ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> updatedAtCaptor =
                ArgumentCaptor.forClass(Instant.class);

        verify(orderRepository).cancelExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                cutoffCaptor.capture(),
                updatedAtCaptor.capture()
        );

        Instant updatedAt = updatedAtCaptor.getValue();

        assertThat(cancelledCount).isEqualTo(2);
        assertThat(updatedAt).isBetween(beforeCall, afterCall);
        assertThat(cutoffCaptor.getValue())
                .isEqualTo(updatedAt.minus(30, ChronoUnit.MINUTES));

        verifyNoInteractions(ticketProductRepository, userRepository);
    }

    private Order createOrder(Long userId) {
        User user = User.builder()
                .email("cancel@example.com")
                .password("encoded-password")
                .nickname("취소회원")
                .build();

        // DB 저장 없이 소유자 비교를 검증하도록 테스트용 ID를 설정한다.
        ReflectionTestUtils.setField(user, "id", userId);

        Order order = Order.create(user, 60000);
        ReflectionTestUtils.setField(order, "id", 101L);
        return order;
    }
}