package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.domain.payment.service.PaymentCompensationService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentCompensationProcessorTest {

    private static final Long COMPENSATION_ID = 1L;
    private static final String PG_ID = "pg-id";
    private static final ClaimedCompensation CLAIMED =
            new ClaimedCompensation(COMPENSATION_ID, PG_ID, 9999L, "결제 금액 불일치", 1, null);
    // 이전 시도가 있었던 재시도 (점유 시 시도 횟수 2)
    private static final ClaimedCompensation CLAIMED_RETRY =
            new ClaimedCompensation(COMPENSATION_ID, PG_ID, 9999L, "결제 금액 불일치", 2, null);
    private static final ClaimedCompensation PENDING =
            new ClaimedCompensation(COMPENSATION_ID, PG_ID, 9999L, "결제 금액 불일치", 1, "cancel-1");
    private static final ClaimedCompensation PENDING_WITHOUT_ID =
            new ClaimedCompensation(COMPENSATION_ID, PG_ID, 9999L, "결제 금액 불일치", 1, null);
    private static final PaymentCancellationResponse SUCCEEDED =
            new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED);

    @Mock
    private PaymentCompensationService paymentCompensationService;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private PaymentCompensationProcessor paymentCompensationProcessor;

    @Test
    @DisplayName("점유한 보상 요청은 저장된 사유로 PG 결제를 전체 취소하고 완료로 기록한다.")
    void process_claimed_CancelAndRecordDone() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(1), any());
        verify(paymentCompensationService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("점유하지 못한 보상 요청은 PG 취소를 하지 않는다.")
    void process_notClaimed_Skip() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.empty());

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("다른 처리가 보상 요청을 잠그고 있으면 건너뛴다.")
    void process_lockedByOther_Skip() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any()))
                .willThrow(new CannotAcquireLockException("lock wait timeout"));

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verifyNoInteractions(paymentGateway);
        verify(paymentCompensationService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PG 취소가 실패하면 실패로 기록하고 완료로 기록하지 않는다.")
    void process_cancelFailed_RecordFailure() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        given(paymentCompensationService.recordFailure(eq(COMPENSATION_ID), eq(1), any(), any()))
                .willReturn(CompensationStatus.FAILED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordFailure(
                eq(COMPENSATION_ID), eq(1), eq(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage()), any());
        verify(paymentCompensationService, never()).recordDone(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("다른 처리가 먼저 결과를 기록해 상태 전이가 거부되면 실패로 기록하지 않고 건너뛴다.")
    void process_alreadyRecordedByOther_Skip() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);
        willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION))
                .given(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(1), any());

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("즉시 시도는 pgId로 보상 요청을 찾아 처리한다.")
    void processByPgId_exists_Process() {
        //given
        given(paymentCompensationService.findIdByPgId(PG_ID)).willReturn(Optional.of(COMPENSATION_ID));
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);

        //when
        paymentCompensationProcessor.processByPgId(PG_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(1), any());
    }

    @Test
    @DisplayName("즉시 시도 중 예기치 않은 오류가 나도 예외를 던지지 않는다.")
    void processByPgId_unexpectedError_Swallowed() {
        //given
        given(paymentCompensationService.findIdByPgId(PG_ID))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_NOT_FOUND));

        //when & then
        assertThatCode(() -> paymentCompensationProcessor.processByPgId(PG_ID)).doesNotThrowAnyException();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("처리 대상 보상 요청을 하나씩 처리하고, 한 건의 예외가 다음 처리를 막지 않는다.")
    void processPending_oneFails_ContinueNext() {
        //given
        given(paymentCompensationService.findProcessableIds(any(), eq(10))).willReturn(List.of(1L, 2L));
        given(paymentCompensationService.claim(eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_NOT_FOUND));
        given(paymentCompensationService.claim(eq(2L), any())).willReturn(Optional.empty());

        //when
        paymentCompensationProcessor.processPending(10);

        //then
        verify(paymentCompensationService).claim(eq(2L), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("PG가 취소를 접수만 했으면 완료로 기록하지 않고 취소 내역 ID와 함께 PG 확인 대기로 기록한다.")
    void process_cancelRequested_RecordPgPending() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null))
                .willReturn(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED));

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgPending(eq(COMPENSATION_ID), eq(1), eq("cancel-1"), any());
        verify(paymentCompensationService, never()).recordDone(anyLong(), anyInt(), any());
        verify(paymentCompensationService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PG 취소 결과를 해석할 수 없으면 취소됐을 수 있으므로 취소 내역 ID 없이 PG 확인 대기로 기록한다.")
    void process_cancelUnknown_RecordPgPendingWithoutId() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null))
                .willReturn(new PaymentCancellationResponse(null, GatewayCancellationStatus.UNKNOWN));

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgPending(eq(COMPENSATION_ID), eq(1), isNull(), any());
        verify(paymentCompensationService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PG 취소 실패를 기록하려는 사이 다른 처리가 다시 점유했으면 실패를 기록하지 않고 예외도 던지지 않는다.")
    void process_reclaimedByOtherBeforeFailureRecord_Skip() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        given(paymentCompensationService.recordFailure(eq(COMPENSATION_ID), eq(1), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION));

        //when & then
        assertThatCode(() -> paymentCompensationProcessor.process(COMPENSATION_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PG 확인 대기 중인 보상은 취소를 다시 보내지 않고, 조회한 취소 내역이 완료면 완료로 기록한다.")
    void confirmPending_cancellationSucceeded_RecordPgCancelled() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED))));

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgCancelled(eq(COMPENSATION_ID), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("조회한 취소 내역이 실패면 PG 취소 실패로 기록해 취소를 다시 시도하게 한다.")
    void confirmPending_cancellationFailed_RecordPgCancelFailed() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.PAID,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.FAILED))));
        given(paymentCompensationService.recordPgCancelFailed(eq(COMPENSATION_ID), any(), any()))
                .willReturn(CompensationStatus.FAILED);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgCancelFailed(eq(COMPENSATION_ID), any(), any());
        verify(paymentCompensationService, never()).recordPgCancelled(anyLong(), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("조회한 취소 내역이 아직 요청 상태면 다음 확인을 예약한다.")
    void confirmPending_stillRequested_Reschedule() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.PAID,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED))));
        given(paymentCompensationService.reschedulePgCheck(eq(COMPENSATION_ID), any()))
                .willReturn(CompensationStatus.PG_PENDING);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).reschedulePgCheck(eq(COMPENSATION_ID), any());
        verify(paymentCompensationService, never()).recordPgCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("조회 결과에 해당 취소 내역이 없으면 확정하지 않고 다음 확인을 예약한다.")
    void confirmPending_cancellationNotFound_Reschedule() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED,
                new PaymentCancellationResponse("cancel-other", GatewayCancellationStatus.SUCCEEDED))));
        given(paymentCompensationService.reschedulePgCheck(eq(COMPENSATION_ID), any()))
                .willReturn(CompensationStatus.PG_PENDING);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).reschedulePgCheck(eq(COMPENSATION_ID), any());
        verify(paymentCompensationService, never()).recordPgCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("PG 조회에 실패하면 취소가 반영됐을 수 있으므로 실패로 기록하지 않고 다음 확인을 예약한다.")
    void confirmPending_lookupFailed_Reschedule() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        given(paymentCompensationService.reschedulePgCheck(eq(COMPENSATION_ID), any()))
                .willReturn(CompensationStatus.PG_PENDING);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).reschedulePgCheck(eq(COMPENSATION_ID), any());
        verify(paymentCompensationService, never()).recordPgCancelFailed(anyLong(), any(), any());
    }

    @Test
    @DisplayName("PG에 결제가 없으면 확정하지 않고 다음 확인을 예약한다.")
    void confirmPending_pgPaymentNotFound_Reschedule() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.empty());
        given(paymentCompensationService.reschedulePgCheck(eq(COMPENSATION_ID), any()))
                .willReturn(CompensationStatus.PG_PENDING);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).reschedulePgCheck(eq(COMPENSATION_ID), any());
    }

    @Test
    @DisplayName("취소 내역 ID가 없으면 PG 결제가 전체 취소됐을 때 완료로 기록한다.")
    void confirmPending_withoutIdAndPgCancelled_RecordPgCancelled() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any()))
                .willReturn(Optional.of(PENDING_WITHOUT_ID));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED)));

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgCancelled(eq(COMPENSATION_ID), any());
    }

    @Test
    @DisplayName("취소 내역 ID가 없고 PG 결제가 아직 결제 완료면 다음 확인을 예약한다.")
    void confirmPending_withoutIdAndPgPaid_Reschedule() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any()))
                .willReturn(Optional.of(PENDING_WITHOUT_ID));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.PAID)));
        given(paymentCompensationService.reschedulePgCheck(eq(COMPENSATION_ID), any()))
                .willReturn(CompensationStatus.PG_PENDING);

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).reschedulePgCheck(eq(COMPENSATION_ID), any());
        verify(paymentCompensationService, never()).recordPgCancelled(anyLong(), any());
    }

    @Test
    @DisplayName("확인할 차례가 아니거나 이미 확정된 보상은 PG를 조회하지 않는다.")
    void confirmPending_notCheckable_Skip() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.empty());

        //when
        paymentCompensationProcessor.confirmPending(COMPENSATION_ID);

        //then
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("다른 처리가 먼저 확인 결과를 기록해 상태 전이가 거부되면 예외 없이 건너뛴다.")
    void confirmPending_alreadyRecordedByOther_Skip() {
        //given
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED))));
        willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION))
                .given(paymentCompensationService).recordPgCancelled(eq(COMPENSATION_ID), any());

        //when & then
        assertThatCode(() -> paymentCompensationProcessor.confirmPending(COMPENSATION_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("스케줄러는 PG 취소 대상을 처리한 뒤 PG 확인 대기 중인 보상을 조회로 확인한다.")
    void processPending_pgCheckable_Confirm() {
        //given
        given(paymentCompensationService.findProcessableIds(any(), eq(10))).willReturn(List.of());
        given(paymentCompensationService.findPgCheckableIds(any(), eq(10))).willReturn(List.of(COMPENSATION_ID));
        given(paymentCompensationService.findPgCheckable(eq(COMPENSATION_ID), any())).willReturn(Optional.of(PENDING));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED))));

        //when
        paymentCompensationProcessor.processPending(10);

        //then
        verify(paymentCompensationService).recordPgCancelled(eq(COMPENSATION_ID), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("첫 시도는 보낸 취소가 없으므로 PG를 조회하지 않고 바로 취소한다.")
    void process_firstAttempt_CancelWithoutLookup() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentGateway, never()).getPayment(anyString());
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(1), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 PG 결제가 이미 취소됐으면 취소를 다시 보내지 않고 완료로 기록한다.")
    void process_retryAndPgCancelled_RecordDoneWithoutCancel() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED_RETRY));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.CANCELLED,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED))));

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(2), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 접수된 취소가 있으면 취소를 다시 보내지 않고 그 내역으로 PG 확인 대기로 기록한다.")
    void process_retryAndRequestedCancellationExists_RecordPgPendingWithoutCancel() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED_RETRY));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.PAID,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED))));

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordPgPending(eq(COMPENSATION_ID), eq(2), eq("cancel-1"), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 이전 취소가 반영되지 않았으면 취소를 다시 보낸다.")
    void process_retryAndNotCancelled_Cancel() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED_RETRY));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.of(pgPayment(GatewayPaymentStatus.PAID,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.FAILED))));
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), eq(2), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 PG에 결제가 없으면 기존처럼 취소를 보낸다.")
    void process_retryAndPgPaymentNotFound_Cancel() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED_RETRY));
        given(paymentGateway.getPayment(PG_ID)).willReturn(Optional.empty());
        given(paymentGateway.cancelPayment(PG_ID, "결제 금액 불일치", null)).willReturn(SUCCEEDED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
    }

    @Test
    @DisplayName("재시도 전 조회에 실패하면 결과를 모른 채 취소를 다시 보내지 않고 실패로 기록한다.")
    void process_retryAndLookupFailed_RecordFailureWithoutCancel() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED_RETRY));
        given(paymentGateway.getPayment(PG_ID)).willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        given(paymentCompensationService.recordFailure(eq(COMPENSATION_ID), eq(2), any(), any()))
                .willReturn(CompensationStatus.FAILED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordFailure(
                eq(COMPENSATION_ID), eq(2), eq(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage()), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), any());
    }

    private PaymentGatewayResponse pgPayment(GatewayPaymentStatus status, PaymentCancellationResponse... cancellations) {
        return new PaymentGatewayResponse(PG_ID, status, 9999L, null, List.of(cancellations));
    }
}
