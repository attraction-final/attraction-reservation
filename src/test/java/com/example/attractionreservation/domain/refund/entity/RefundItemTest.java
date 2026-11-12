package com.example.attractionreservation.domain.refund.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;

class RefundItemTest {

    @Test
    @DisplayName("남은 환불 가능 수량만큼 환불하면 주문 당시 가격으로 환불 금액을 계산하고 주문 수량은 바꾸지 않는다.")
    void of_refundableQuantity_Success() {
        //given
        OrderItem orderItem = createOrderItem(10000, 3);

        //when
        RefundItem refundItem = RefundItem.of(orderItem, 2, 2);

        //then
        assertEquals(2, refundItem.getQuantity());
        assertEquals(20000, refundItem.getRefundPrice());
        assertEquals(3, orderItem.getQuantity());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("환불 수량이 0 이하면 환불할 수 없다.")
    void of_notPositiveQuantity_Fail(int quantity) {
        //given
        OrderItem orderItem = createOrderItem(10000, 3);

        //when & then
        assertThatThrownBy(() -> RefundItem.of(orderItem, quantity, 3))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
    }

    @Test
    @DisplayName("남은 환불 가능 수량을 넘으면 환불할 수 없다.")
    void of_exceedRefundableQuantity_Fail() {
        //given
        OrderItem orderItem = createOrderItem(10000, 3);

        //when & then
        assertThatThrownBy(() -> RefundItem.of(orderItem, 2, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
    }

    @Test
    @DisplayName("남은 환불 가능 수량이 없으면 환불할 수 없다.")
    void of_noRefundableQuantity_Fail() {
        //given
        OrderItem orderItem = createOrderItem(10000, 3);

        //when & then
        assertThatThrownBy(() -> RefundItem.of(orderItem, 1, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
    }

    private OrderItem createOrderItem(int price, int quantity) {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        Order order = Order.create(user, price * quantity, LocalDate.of(2026, 10, 25));

        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        return orderItem;
    }
}
