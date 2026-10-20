package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 금액이 0보다 크면 결제를 생성할 수 있다.")
    void createPayment_paymentAmountLargerThan0_Success() {
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
        paymentService.createPayment(order);

        //then
        ArgumentCaptor<Payment> paymentArgumentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentArgumentCaptor.capture());
        Payment payment = paymentArgumentCaptor.getValue();

        assertEquals(10000, payment.getAmount());
        assertNotNull(payment.getOrder());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    @DisplayName("결제 금액이 0이하면 결제를 생성할 수 없다.")
    void createPayment_paymentAmountLessOrEqualTo0_Fail() {
        //given
        Order order = Order.create(
                User.builder()
                        .email("test@email.com")
                        .password("password")
                        .nickname("name")
                        .build(),
                0
        );

        //when&then
        assertThatThrownBy(() -> paymentService.createPayment(order))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());
    }

    @Test
    @DisplayName("결제 금액이 음수면 결제를 생성할 수 없다.")
    void createPayment_amountIsNegative_Fail() {
        //given
        Order order = Order.create(
                User.builder()
                        .email("test@email.com")
                        .password("password")
                        .nickname("name")
                        .build(),
                -1
        );

        //when&then
        assertThatThrownBy(() -> paymentService.createPayment(order))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INVALID_AMOUNT.getMessage());

        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("본인의 결제 대기 상태 결제는 취소할 수 있다.")
    void cancelPayment_pending_Success() {
        //given
        Payment payment = createPayment(USER_ID);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when
        paymentService.cancelPayment(USER_ID, ORDER_ID);

        //then
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
    }

    @Test
    @DisplayName("존재하지 않는 결제는 취소할 수 없다.")
    void cancelPayment_notFound_Fail() {
        //given
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentService.cancelPayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("다른 사용자의 결제는 취소할 수 없다.")
    void cancelPayment_otherUser_Fail() {
        //given
        Long otherUserId = 2L;
        Payment payment = createPayment(otherUserId);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when & then
        assertThatThrownBy(() -> paymentService.cancelPayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아닌 결제는 취소할 수 없다.")
    void cancelPayment_notPending_Fail(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(USER_ID, status);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when & then
        assertThatThrownBy(() -> paymentService.cancelPayment(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCEL_PROHIBITED.getMessage());
        assertEquals(status, payment.getStatus());
    }

    private Payment createPayment(Long userId) {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        Order order = Order.create(user, 10000);
        return Payment.create(order);
    }

    private Payment createPaymentWithStatus(Long userId, PaymentStatus status) {
        Payment payment = createPayment(userId);
        switch (status) {
            case PENDING -> {
            }
            case PAID -> payment.markAsPaid();
            case PARTIALLY_REFUNDED -> {
                payment.markAsPaid();
                payment.markAsPartiallyRefunded();
            }
            case REFUNDED -> {
                payment.markAsPaid();
                payment.markAsRefunded();
            }
            case FAILED -> payment.markAsFailed();
            case CANCELLED -> payment.markAsCancelled();
        }
        return payment;
    }
}