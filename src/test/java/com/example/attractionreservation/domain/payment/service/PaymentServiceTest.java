package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
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
    private static final Instant APPROVED_AT = Instant.parse("2026-10-20T01:00:00Z");

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
        String pgId = paymentService.createPayment(order);

        //then
        ArgumentCaptor<Payment> paymentArgumentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentArgumentCaptor.capture());
        Payment payment = paymentArgumentCaptor.getValue();

        assertEquals(10000, payment.getAmount());
        assertNotNull(payment.getOrder());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNotNull(pgId);
        assertEquals(payment.getPgId(), pgId);
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
    void cancelledByUser_pending_Success() {
        //given
        Payment payment = createPayment(USER_ID);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when
        paymentService.cancelledByUser(USER_ID, ORDER_ID);

        //then
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, payment.getOrder().getStatus());
    }

    @Test
    @DisplayName("존재하지 않는 결제는 취소할 수 없다.")
    void cancelledByUser_notFound_Fail() {
        //given
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentService.cancelledByUser(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("다른 사용자의 결제는 취소할 수 없다.")
    void cancelledByUser_otherUser_Fail() {
        //given
        Long otherUserId = 2L;
        Payment payment = createPayment(otherUserId);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when & then
        assertThatThrownBy(() -> paymentService.cancelledByUser(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아닌 결제는 취소할 수 없다.")
    void cancelledByUser_notPending_Fail(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(USER_ID, status);
        given(paymentRepository.findWithLockByOrderId(ORDER_ID)).willReturn(Optional.of(payment));

        //when & then
        assertThatThrownBy(() -> paymentService.cancelledByUser(USER_ID, ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_CANCEL_PROHIBITED.getMessage());
        assertEquals(status, payment.getStatus());
        assertEquals(OrderStatus.PENDING, payment.getOrder().getStatus());
    }

    @Test
    @DisplayName("본인의 결제면 결제를 조회할 수 있다.")
    void getOwnedPayment_owner_Success() {
        //given
        Payment payment = createPayment(USER_ID);
        given(paymentRepository.findByPgIdAndUserId(payment.getPgId(), USER_ID)).willReturn(Optional.of(payment));

        //when
        Payment found = paymentService.getOwnedPayment(USER_ID, payment.getPgId());

        //then
        assertEquals(payment, found);
    }

    @Test
    @DisplayName("다른 사용자의 결제거나 없는 결제면 결제를 찾을 수 없다는 예외가 발생한다.")
    void getOwnedPayment_notOwner_Fail() {
        //given
        given(paymentRepository.findByPgIdAndUserId("pg-id", USER_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentService.getOwnedPayment(USER_ID, "pg-id"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("결제 목록 조회 시 조회 조건을 그대로 전달하고 결과를 페이지 응답으로 변환한다.")
    void findAllPaymentsByUserId_validCondition_ReturnsPageableDto() {
        //given
        Long userId = 1L;
        SearchPaymentCondition condition = new SearchPaymentCondition(PaymentStatus.PAID);
        Pageable pageable = PageRequest.of(0, 10);
        List<PaymentResponse> content = List.of(
                new PaymentResponse(1L, 10000, PaymentStatus.PAID, Instant.now())
        );
        given(paymentRepository.searchAllByUserId(userId, pageable, condition))
                .willReturn(new PageImpl<>(content, pageable, 1));

        //when
        PageableDto<PaymentResponse> result = paymentService.findAllPaymentsByUserId(userId, condition, pageable);

        //then
        verify(paymentRepository).searchAllByUserId(userId, pageable, condition);
        assertEquals(content, result.content());
        assertEquals(1, result.totalElements());
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
