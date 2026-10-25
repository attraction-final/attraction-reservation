package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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

    @Test
    @DisplayName("본인의 결제를 단건 조회하면 결제 정보를 반환한다.")
    void findPaymentById_ownPayment_ReturnsPaymentDetailResponse() {
        //given
        Long userId = 1L;
        Long paymentId = 10L;
        Payment payment = createPayment();
        payment.markAsPaid();
        given(paymentRepository.findByIdAndOrderUserId(paymentId, userId)).willReturn(Optional.of(payment));

        //when
        PaymentDetailResponse result = paymentService.findPaymentById(userId, paymentId);

        //then
        assertEquals(10000, result.amount());
        assertEquals(PaymentStatus.PAID, result.status());
        assertEquals(payment.getPaidAt(), result.paidAt());
        assertEquals(10000, result.order().totalAmount());
        assertEquals(OrderStatus.PENDING, result.order().status());
    }

    @Test
    @DisplayName("결제가 없거나 다른 사용자의 결제면 예외가 발생한다.")
    void findPaymentById_notFoundOrNotOwned_ThrowsException() {
        //given
        Long userId = 1L;
        Long paymentId = 10L;
        given(paymentRepository.findByIdAndOrderUserId(paymentId, userId)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentService.findPaymentById(userId, paymentId))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
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