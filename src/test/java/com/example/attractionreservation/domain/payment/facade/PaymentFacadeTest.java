package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.domain.payment.service.PaymentCommandService;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final Instant PAID_AT = Instant.parse("2026-10-20T01:00:00Z");

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentCommandService paymentCommandService;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private PaymentFacade paymentFacade;

    private Payment payment;
    private String pgId;
    private ConfirmPaymentRequest request;

    @BeforeEach
    void setUp() {
        payment = createPayment();
        pgId = payment.getPgId();
        request = new ConfirmPaymentRequest(ORDER_ID);
    }

    @Test
    @DisplayName("PG에서 결제 완료됐고 금액이 일치하면 결제를 승인한다.")
    void confirmPayment_paid_Success() {
        //given
        ConfirmPaymentResponse approved = ConfirmPaymentResponse.from(payment);
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(pgResponse(GatewayPaymentStatus.PAID, 10000L)));
        given(paymentCommandService.approvePayment(pgId, PAID_AT)).willReturn(approved);

        //when
        ConfirmPaymentResponse response = paymentFacade.confirmPayment(USER_ID, pgId, request);

        //then
        assertEquals(approved, response);
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("다른 사용자의 결제면 PG를 호출하지 않고 거절한다.")
    void confirmPayment_notOwner_Fail() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        verifyNoInteractions(paymentGateway, paymentCommandService);
    }

    @Test
    @DisplayName("요청한 주문 ID가 결제의 주문과 다르면 PG를 호출하지 않고 거절한다.")
    void confirmPayment_orderIdMismatch_Fail() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        ConfirmPaymentRequest mismatched = new ConfirmPaymentRequest(ORDER_ID + 1);

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, mismatched))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_INPUT_VALUE.getMessage());
        verifyNoInteractions(paymentGateway, paymentCommandService);
    }

    @Test
    @DisplayName("이미 결제 완료된 결제면 PG를 호출하지 않고 현재 결제 정보를 반환한다.")
    void confirmPayment_alreadyPaid_SkipGateway() {
        //given
        payment.markAsPaid(PAID_AT);
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);

        //when
        ConfirmPaymentResponse response = paymentFacade.confirmPayment(USER_ID, pgId, request);

        //then
        assertEquals(PaymentStatus.PAID, response.status());
        verifyNoInteractions(paymentGateway, paymentCommandService);
    }

    @Test
    @DisplayName("이미 실패 처리된 결제면 PG를 호출하지 않고 거절한다.")
    void confirmPayment_alreadyFailed_Fail() {
        //given
        payment.markAsFailed();
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_ALREADY_PROCESSED.getMessage());
        verifyNoInteractions(paymentGateway, paymentCommandService);
    }

    @Test
    @DisplayName("PG에 결제가 없으면 승인하지 않는다.")
    void confirmPayment_pgPaymentNotFound_Fail() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND.getMessage());
        verifyNoInteractions(paymentCommandService);
    }

    @Test
    @DisplayName("PG에서 결제가 실패했으면 결제를 실패 처리하고 예외를 던진다.")
    void confirmPayment_pgFailed_FailPayment() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(pgResponse(GatewayPaymentStatus.FAILED, 10000L)));

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_PG_FAILED.getMessage());
        verify(paymentCommandService).failPayment(pgId);
        verify(paymentCommandService, never()).approvePayment(anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = GatewayPaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PAID", "FAILED"})
    @DisplayName("PG에서 결제가 완료되지 않았으면 실패 처리하지 않고 결제 미완료 예외를 던진다.")
    void confirmPayment_pgNotPaid_Fail(GatewayPaymentStatus pgStatus) {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(pgResponse(pgStatus, 10000L)));

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_PAID.getMessage());
        verifyNoInteractions(paymentCommandService);
    }

    @Test
    @DisplayName("PG 결제 금액이 원장과 다르면 결제를 실패 처리한 뒤 PG 결제를 취소한다.")
    void confirmPayment_amountMismatch_CancelPgPayment() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(pgResponse(GatewayPaymentStatus.PAID, 9999L)));
        given(paymentCommandService.failPayment(pgId)).willReturn(true);

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INCORRECT_AMOUNT.getMessage());
        verify(paymentGateway).cancelPayment(pgId, "결제 금액 불일치", 9999L);
        verify(paymentCommandService, never()).approvePayment(anyString(), any());
    }

    @Test
    @DisplayName("금액 불일치 결제가 이미 다른 요청에서 처리됐으면 PG 취소를 하지 않는다.")
    void confirmPayment_amountMismatchAlreadyProcessed_SkipCancel() {
        //given
        given(paymentService.getOwnedPayment(USER_ID, pgId)).willReturn(payment);
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(pgResponse(GatewayPaymentStatus.PAID, 9999L)));
        given(paymentCommandService.failPayment(pgId)).willReturn(false);

        //when & then
        assertThatThrownBy(() -> paymentFacade.confirmPayment(USER_ID, pgId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_INCORRECT_AMOUNT.getMessage());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    private PaymentGatewayResponse pgResponse(GatewayPaymentStatus status, Long amount) {
        return new PaymentGatewayResponse(pgId, status, amount, status == GatewayPaymentStatus.PAID ? PAID_AT : null);
    }

    private Payment createPayment() {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        Order order = Order.create(user, 10000);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        return Payment.create(order);
    }
}
