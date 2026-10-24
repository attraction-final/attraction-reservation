package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final String PG_ID = "pg-id";
    private static final Instant APPROVED_AT = Instant.parse("2026-10-20T01:00:00Z");

    @Mock
    private PaymentRepository paymentRepository;

    @InjectMocks
    private PaymentCommandService paymentCommandService;

    @Test
    @DisplayName("결제 대기 상태의 결제를 승인하면 결제와 주문이 결제 완료 상태가 된다.")
    void approvePayment_pending_Success() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT);

        //then
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(APPROVED_AT, payment.getPaidAt());
        assertEquals(OrderStatus.COMPLETED, payment.getOrder().getStatus());
        assertEquals(PaymentStatus.PAID, response.status());
    }

    @Test
    @DisplayName("이미 결제 완료된 결제를 다시 승인하면 예외 없이 기존 결제 시각을 유지한다.")
    void approvePayment_alreadyPaid_Skip() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PAID);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT.plusSeconds(5));

        //then
        assertEquals(PaymentStatus.PAID, payment.getStatus());
        assertEquals(APPROVED_AT, payment.getPaidAt());
        assertEquals(APPROVED_AT, response.paidAt());
    }

    @Test
    @DisplayName("실패 처리된 결제는 승인할 수 없다.")
    void approvePayment_failed_Fail() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.FAILED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when & then
        assertThatThrownBy(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_ALREADY_PROCESSED.getMessage());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    @DisplayName("존재하지 않는 결제는 승인할 수 없다.")
    void approvePayment_notFound_Fail() {
        //given
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("결제 대기 상태의 결제를 실패 처리하면 결제는 실패, 주문은 취소 상태가 되고 true를 반환한다.")
    void failPayment_pending_Success() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        boolean failed = paymentCommandService.failPayment(PG_ID);

        //then
        assertTrue(failed);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, payment.getOrder().getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아닌 결제는 실패 처리하지 않고 false를 반환한다.")
    void failPayment_notPending_Skip(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(status);
        OrderStatus orderStatus = payment.getOrder().getStatus();
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        boolean failed = paymentCommandService.failPayment(PG_ID);

        //then
        assertFalse(failed);
        assertEquals(status, payment.getStatus());
        assertEquals(orderStatus, payment.getOrder().getStatus());
    }

    @Test
    @DisplayName("존재하지 않는 결제는 실패 처리할 수 없다.")
    void failPayment_notFound_Fail() {
        //given
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentCommandService.failPayment(PG_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }

    private Payment createPaymentWithStatus(PaymentStatus status) {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        Payment payment = Payment.create(Order.create(user, 10000));
        switch (status) {
            case PENDING -> {
            }
            case PAID -> payment.markAsPaid(APPROVED_AT);
            case PARTIALLY_REFUNDED -> {
                payment.markAsPaid(APPROVED_AT);
                payment.markAsPartiallyRefunded();
            }
            case REFUNDED -> {
                payment.markAsPaid(APPROVED_AT);
                payment.markAsRefunded();
            }
            case FAILED -> payment.markAsFailed();
            case CANCELLED -> payment.markAsCancelled();
        }
        return payment;
    }
}
