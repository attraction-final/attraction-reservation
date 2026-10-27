package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentCommandServiceTest {

    private static final Long USER_ID = 1L;
    private static final String PG_ID = "pg-id";
    private static final Instant APPROVED_AT = Instant.parse("2026-10-20T01:00:00Z");
    private static final Long PG_AMOUNT = 10000L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentCompensationService paymentCompensationService;

    @Mock
    private TicketProductService ticketProductService;

    @InjectMocks
    private PaymentCommandService paymentCommandService;

    @Test
    @DisplayName("결제 대기 상태의 결제를 승인하면 결제와 주문이 결제 완료 상태가 된다.")
    void approvePayment_pending_Success() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT);

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
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT.plusSeconds(5), PG_AMOUNT);

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
        assertThatThrownBy(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_ALREADY_PROCESSED.getMessage());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    @DisplayName("사용자가 취소한 결제는 승인하지 않고, 같은 트랜잭션에서 PG 결제 금액으로 보상 요청을 저장한 뒤 CANCELLED를 반환한다.")
    void approvePayment_cancelled_SaveCompensation() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.empty());

        //when
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT);

        //then
        assertEquals(PaymentStatus.CANCELLED, response.status());
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(paymentCompensationService).saveCompensatingEvent(PG_ID, PG_AMOUNT, "취소된 주문의 결제");
    }

    @Test
    @DisplayName("취소된 결제에 보상 요청이 이미 있으면 다시 저장하지 않고 CANCELLED를 반환한다.")
    void approvePayment_cancelledAlreadyRequested_SkipCompensation() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.of(1L));

        //when
        ConfirmPaymentResponse response = paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT);

        //then
        assertEquals(PaymentStatus.CANCELLED, response.status());
        verify(paymentCompensationService, never()).saveCompensatingEvent(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("취소된 결제의 보상 요청 저장에 실패하면 예외를 그대로 던져 트랜잭션이 롤백되고 호출하는 쪽이 재시도하게 한다.")
    void approvePayment_cancelledCompensationSaveFailed_Throw() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.empty());
        willThrow(new DataIntegrityViolationException("duplicate pg_id"))
                .given(paymentCompensationService).saveCompensatingEvent(PG_ID, PG_AMOUNT, "취소된 주문의 결제");

        //when & then
        assertThatThrownBy(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CANCELLED")
    @DisplayName("취소된 결제가 아니면 승인 결과와 관계없이 보상 요청을 저장하지 않는다.")
    void approvePayment_notCancelled_NoCompensation(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(status);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        catchThrowable(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT));

        //then
        verify(paymentCompensationService, never()).saveCompensatingEvent(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("존재하지 않는 결제는 승인할 수 없다.")
    void approvePayment_notFound_Fail() {
        //given
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> paymentCommandService.approvePayment(PG_ID, APPROVED_AT, PG_AMOUNT))
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
    @DisplayName("PG 결제 실패로 실패 처리할 때는 보상 요청을 저장하지 않는다.")
    void failPayment_pending_NoCompensation() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        paymentCommandService.failPayment(PG_ID);

        //then
        verifyNoInteractions(paymentCompensationService);
    }

    @Test
    @DisplayName("금액 불일치로 실패 처리하면 결제 실패·주문 취소와 함께 PG 결제 금액으로 보상 요청을 저장한다.")
    void failPaymentWithCompensation_pending_SaveCompensation() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        boolean failed = paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        assertTrue(failed);
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        assertEquals(OrderStatus.CANCELLED, payment.getOrder().getStatus());
        verify(paymentCompensationService).saveCompensatingEvent(PG_ID, 9999L, "결제 금액 불일치");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"PENDING", "CANCELLED"})
    @DisplayName("이미 처리된 결제는 실패 처리하지 않고 보상 요청도 저장하지 않는다.")
    void failPaymentWithCompensation_notPending_Skip(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(status);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        boolean failed = paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        assertFalse(failed);
        assertEquals(status, payment.getStatus());
        verifyNoInteractions(paymentCompensationService);
    }

    @Test
    @DisplayName("사용자가 취소한 결제가 금액 불일치면 실패 처리 없이 보상 요청만 저장한다.")
    void failPaymentWithCompensation_cancelled_SaveCompensation() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.empty());

        //when
        boolean requested = paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        assertTrue(requested);
        assertEquals(PaymentStatus.CANCELLED, payment.getStatus());
        verify(paymentCompensationService).saveCompensatingEvent(PG_ID, 9999L, "결제 금액 불일치");
    }

    @Test
    @DisplayName("사용자가 취소한 결제에 보상 요청이 이미 있으면 금액 불일치여도 보상 요청을 저장하지 않는다.")
    void failPaymentWithCompensation_cancelledAlreadyRequested_Skip() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.of(1L));

        //when
        boolean requested = paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        assertFalse(requested);
        verify(paymentCompensationService, never()).saveCompensatingEvent(anyString(), any(), anyString());
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

    @Test
    @DisplayName("결제 실패 처리하면 주문 항목의 상품별 수량을 합산해 재고를 복구한다.")
    void failPayment_pending_RestoresStock() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        addItem(payment, 10L, 2);
        addItem(payment, 10L, 3);
        addItem(payment, 20L, 1);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        paymentCommandService.failPayment(PG_ID);

        //then
        verify(ticketProductService).updateTicketProductsStockIncrease(Map.of(10L, 5, 20L, 1));
    }

    @Test
    @DisplayName("금액 불일치로 실패 처리해도 재고를 복구한다.")
    void failPaymentWithCompensation_pending_RestoresStock() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.PENDING);
        addItem(payment, 10L, 2);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        verify(ticketProductService).updateTicketProductsStockIncrease(Map.of(10L, 2));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("결제 대기 상태가 아니면 실패 처리하지 않으므로 재고를 복구하지 않는다.")
    void failPayment_notPending_DoesNotRestoreStock(PaymentStatus status) {
        //given
        Payment payment = createPaymentWithStatus(status);
        addItem(payment, 10L, 2);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));

        //when
        paymentCommandService.failPayment(PG_ID);

        //then
        verifyNoInteractions(ticketProductService);
    }

    @Test
    @DisplayName("사용자가 이미 취소한 결제는 보상 요청만 저장하고 재고를 다시 복구하지 않는다.")
    void failPaymentWithCompensation_cancelled_DoesNotRestoreStock() {
        //given
        Payment payment = createPaymentWithStatus(PaymentStatus.CANCELLED);
        addItem(payment, 10L, 2);
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(payment));
        given(paymentCompensationService.findIdByPgId(payment.getPgId())).willReturn(Optional.empty());

        //when
        paymentCommandService.failPaymentWithCompensation(PG_ID, 9999L, "결제 금액 불일치");

        //then
        verifyNoInteractions(ticketProductService);
    }

    private void addItem(Payment payment, Long productId, int quantity) {
        TicketProduct product = new TicketProduct(
                "테스트상품", 5000, "테스트설명", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        ReflectionTestUtils.setField(product, "id", productId);
        payment.getOrder().addOrderItem(product, quantity);
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
