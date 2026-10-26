package com.example.attractionreservation.domain.order.entity;

import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderTest {

    @Test
    @DisplayName("결제 대기 상태의 주문은 결제 완료로 변경할 수 있다.")
    void markAsPaid_pending_Success() {
        //given
        Order order = createOrder();

        //when
        order.markAsPaid();

        //then
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아닌 주문은 결제 완료로 변경할 수 없다.")
    void markAsPaid_notPending_Fail(OrderStatus status) {
        //given
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "status", status);

        //when & then
        assertThatThrownBy(order::markAsPaid)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.ORDER_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, order.getStatus());
    }

    @Test
    @DisplayName("결제 대기 상태의 주문은 취소할 수 있다.")
    void markAsCancelled_pending_Success() {
        //given
        Order order = createOrder();

        //when
        order.markAsCancelled();

        //then
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아닌 주문은 취소할 수 없다.")
    void markAsCancelled_notPending_Fail(OrderStatus status) {
        //given
        Order order = createOrder();
        ReflectionTestUtils.setField(order, "status", status);

        //when & then
        assertThatThrownBy(order::markAsCancelled)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.ORDER_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(status, order.getStatus());
    }

    private Order createOrder() {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        return Order.create(user, 10000);
    }
}
