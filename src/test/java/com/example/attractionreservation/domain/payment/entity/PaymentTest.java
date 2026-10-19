package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

    @Test
    @DisplayName("결제 금액이 0보다 크면 결제 원장을 생성할 수 있다.")
    void create_amountLargerThan0_Success() {
        //given
        Order order = Order.create(
                User.builder()
                        .email("test@email.com")
                        .password("password")
                        .nickname("name")
                        .build(),
                10000
        );

        //when
        Payment payment = Payment.create(order, 10000);

        //then
        assertEquals(10000, payment.getAmount());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    @DisplayName("결제 금액이 0이하면 결제 원장을 생성할 수 없다.")
    void create_amountLessOrEqualTo0_Fail() {
        //given
        Order order = Order.create(
                User.builder()
                        .email("test@email.com")
                        .password("password")
                        .nickname("name")
                        .build(),
                0
        );

        //when
        assertThatThrownBy(() -> Payment.create(order, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_AMOUNT.getMessage());
    }
}