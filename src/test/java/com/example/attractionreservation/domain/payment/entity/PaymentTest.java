package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

class PaymentTest {

    private static final Instant PAID_AT = Instant.parse("2026-10-20T01:00:00Z");

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
        Payment payment = Payment.create(order);

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
        assertThatThrownBy(() -> Payment.create(order))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());
    }

    @Test
    @DisplayName("결제 대기 상태면 결제 완료로 변경할 수 있다.")
    void markAsPaid_pending_Success() {
        //given
        Payment payment = createPayment();

        //when
        payment.markAsPaid(PAID_AT);

        //then
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(PAID_AT, payment.getPaidAt());
    }

    @Test
    @DisplayName("결제 완료 시각 없이는 결제 완료로 변경할 수 없다.")
    void markAsPaid_nullPaidAt_Fail() {
        //given
        Payment payment = createPayment();

        //when & then
        assertThatThrownBy(() -> payment.markAsPaid(null))
                .isInstanceOf(NullPointerException.class);
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNull(payment.getPaidAt());
    }

    @Test
    @DisplayName("결제 완료로 변경하면 결제 완료 시각이 기록된다.")
    void markAsPaid_pending_PaidAtRecorded() {
        //given
        Payment payment = createPayment();
        Instant before = Instant.now();

        //when
        payment.markAsPaid(Instant.now());
        Instant after = Instant.now();

        //then
        assertNotNull(payment.getPaidAt());
        assertFalse(payment.getPaidAt().isBefore(before));
        assertFalse(payment.getPaidAt().isAfter(after));
    }

    @Test
    @DisplayName("이미 결제 완료 상태면 다시 결제 완료로 변경할 수 없다.")
    void markAsPaid_alreadyPaid_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);

        //when & then
        assertThatThrownBy(() -> payment.markAsPaid(PAID_AT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
    }

    @Test
    @DisplayName("결제 취소 상태면 결제 완료로 변경할 수 없다.")
    void markAsPaid_cancelled_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsCancelled();

        //when & then
        assertThatThrownBy(() -> payment.markAsPaid(PAID_AT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 대기 상태면 결제 취소로 변경할 수 있다.")
    void markAsCancelled_pending_Success() {
        //given
        Payment payment = createPayment();

        //when
        payment.markAsCancelled();

        //then
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 완료 상태면 결제 취소로 변경할 수 없다.")
    void markAsCancelled_paid_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);

        //when & then
        assertThatThrownBy(payment::markAsCancelled)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
    }

    @Test
    @DisplayName("결제 완료 상태면 부분 환불로 변경할 수 있다.")
    void markAsPartiallyRefunded_paid_Success() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);

        //when
        payment.markAsPartiallyRefunded();

        //then
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 대기 상태면 부분 환불로 변경할 수 없다.")
    void markAsPartiallyRefunded_pending_Fail() {
        //given
        Payment payment = createPayment();

        //when & then
        assertThatThrownBy(payment::markAsPartiallyRefunded)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    @DisplayName("이미 부분 환불 상태여도 다시 부분 환불로 변경할 수 있다.")
    void markAsPartiallyRefunded_alreadyPartiallyRefunded_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);
        payment.markAsPartiallyRefunded();

        //when
        payment.markAsPartiallyRefunded();

        //then
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 완료 상태면 전체 환불로 변경할 수 있다.")
    void markAsRefunded_paid_Success() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);

        //when
        payment.markAsRefunded();

        //then
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    @DisplayName("부분 환불 상태면 전체 환불로 변경할 수 있다.")
    void markAsRefunded_partiallyRefunded_Success() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);
        payment.markAsPartiallyRefunded();

        //when
        payment.markAsRefunded();

        //then
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 대기 상태면 전체 환불로 변경할 수 없다.")
    void markAsRefunded_pending_Fail() {
        //given
        Payment payment = createPayment();

        //when & then
        assertThatThrownBy(payment::markAsRefunded)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    @DisplayName("이미 전체 환불 상태면 다시 전체 환불로 변경할 수 없다.")
    void markAsRefunded_alreadyRefunded_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);
        payment.markAsRefunded();

        //when & then
        assertThatThrownBy(payment::markAsRefunded)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 대기 상태면 결제 실패로 변경할 수 있다.")
    void markAsFailed_pending_Success() {
        //given
        Payment payment = createPayment();

        //when
        payment.markAsFailed();

        //then
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 완료 상태면 결제 실패로 변경할 수 없다.")
    void markAsFailed_paid_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsPaid(PAID_AT);

        //when & then
        assertThatThrownBy(payment::markAsFailed)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.PAID, payment.getStatus());
    }

    @Test
    @DisplayName("이미 결제 실패 상태면 다시 결제 실패로 변경할 수 없다.")
    void markAsFailed_alreadyFailed_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsFailed();

        //when & then
        assertThatThrownBy(payment::markAsFailed)
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    @DisplayName("결제 실패 상태면 결제 완료로 변경할 수 없다.")
    void markAsPaid_failed_Fail() {
        //given
        Payment payment = createPayment();
        payment.markAsFailed();

        //when & then
        assertThatThrownBy(() -> payment.markAsPaid(PAID_AT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION.getMessage());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    private Payment createPayment() {
        Order order = Order.create(
                User.builder()
                        .email("test@email.com")
                        .password("password")
                        .nickname("name")
                        .build(),
                10000
        );
        return Payment.create(order);
    }
}
