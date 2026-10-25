package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.domain.refund.dto.ClaimedRefund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundCommandService;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RefundProcessorTest {

    private static final Long REFUND_ID = 1L;
    private static final Instant PENDING_SINCE = Instant.parse("2026-10-25T01:00:00Z");
    // 첫 시도로 점유한 환불: 50,000원 결제에서 20,000원 부분 환불, 앞서 완료된 환불 없음
    private static final ClaimedRefund CLAIMED =
            new ClaimedRefund(REFUND_ID, "pg-id", 20000L, "단순 변심", 50000L, 0L, 1, null, null);
    // 이전 시도가 결과를 기록하지 못해 다시 점유한 환불
    private static final ClaimedRefund RECLAIMED =
            new ClaimedRefund(REFUND_ID, "pg-id", 20000L, "단순 변심", 50000L, 0L, 2, null, null);
    private static final ClaimedRefund PENDING_WITH_ID =
            new ClaimedRefund(REFUND_ID, "pg-id", 20000L, "단순 변심", 50000L, 0L, 1, "cancel-1", PENDING_SINCE);
    private static final ClaimedRefund PENDING_WITHOUT_ID =
            new ClaimedRefund(REFUND_ID, "pg-id", 20000L, "단순 변심", 50000L, 0L, 1, null, PENDING_SINCE);

    @Mock
    private RefundCommandService refundCommandService;

    @Mock
    private PaymentGateway paymentGateway;

    @InjectMocks
    private RefundProcessor refundProcessor;

    @Test
    @DisplayName("점유한 환불은 환불 금액과 PG 취소 가능 금액으로 부분 취소하고 완료를 기록한다.")
    void process_claimed_CancelAndComplete() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordComplete(eq(REFUND_ID), eq(1), any());
        verify(refundCommandService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("점유하지 못하면 PG를 호출하지 않는다.")
    void process_notClaimed_Skip() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.empty());

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("다른 처리가 결제를 잠그고 있어 락을 얻지 못하면 건너뛴다.")
    void process_lockConflict_Skip() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any()))
                .willThrow(new PessimisticLockingFailureException("lock timeout"));

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("PG 취소가 실패하면 실패를 기록하고 완료는 기록하지 않는다.")
    void process_cancelFailed_RecordFailure() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        given(refundCommandService.recordFailure(eq(REFUND_ID), eq(1), anyString(), any())).willReturn(RefundStatus.FAILED);

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordFailure(
                eq(REFUND_ID), eq(1), eq(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage()), any());
        verify(refundCommandService, never()).recordComplete(anyLong(), anyInt(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"PAYMENT_ALREADY_CANCELLED", "PAYMENT_CANCELLABLE_AMOUNT_MISMATCH"})
    @DisplayName("PG에 이미 반영됐을 수 있는 취소 실패는 재시도하지 않고 DEAD로 기록한다.")
    void process_nonRetryableCancelFailure_RecordDead(ErrorCode errorCode) {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(errorCode))
                .given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordDead(REFUND_ID, 1, errorCode.getMessage());
        verify(refundCommandService, never()).recordFailure(anyLong(), anyInt(), any(), any());
        verify(refundCommandService, never()).recordComplete(anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("PG 취소 실패를 기록하려는 사이 취소 웹훅이 먼저 완료 처리했으면 예외를 던지지 않고 건너뛴다.")
    void process_failureRecordAfterSynced_Skip() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        given(refundCommandService.recordFailure(eq(REFUND_ID), eq(1), anyString(), any()))
                .willThrow(new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION));

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = GatewayCancellationStatus.class, names = {"REQUESTED", "UNKNOWN"})
    @DisplayName("PG가 취소를 접수만 했거나 응답을 해석하지 못하면 완료로 보지 않고 취소 내역 ID와 함께 확인 대기로 기록한다.")
    void process_notConfirmed_RecordPgPending(GatewayCancellationStatus status) {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", status));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordPgPending(eq(REFUND_ID), eq(1), eq("cancel-1"), any());
        verify(refundCommandService, never()).recordComplete(anyLong(), anyInt(), any());
        verify(refundCommandService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("첫 시도는 이전에 보낸 취소가 없으므로 PG를 조회하지 않고 바로 취소한다.")
    void process_firstAttempt_CancelWithoutLookup() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(paymentGateway, never()).getPayment(anyString());
    }

    @Test
    @DisplayName("재시도 전 조회에서 금액이 같은 접수된 취소가 있으면 취소를 다시 보내지 않고 그 내역으로 확인 대기로 기록한다.")
    void process_retryWithRequestedCancellation_PgPendingWithoutCancel() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(RECLAIMED));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, 20000L))));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        verify(refundCommandService).recordPgPending(eq(REFUND_ID), eq(2), eq("cancel-1"), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 PG 취소 누적 금액에 이 환불이 반영됐으면 취소를 다시 보내지 않고 완료로 기록한다.")
    void process_retryAlreadyApplied_CompleteWithoutCancel() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(RECLAIMED));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L)));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        verify(refundCommandService).recordComplete(eq(REFUND_ID), eq(2), any());
    }

    @Test
    @DisplayName("재시도 전 조회에서 반영된 취소가 없고 접수된 취소도 금액이 다르면 취소를 보낸다.")
    void process_retryNotApplied_Cancel() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(RECLAIMED));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null,
                new PaymentCancellationResponse("other", GatewayCancellationStatus.REQUESTED, 5000L))));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordComplete(eq(REFUND_ID), eq(2), any());
    }

    @Test
    @DisplayName("재시도 전 조회에 실패하면 결과를 모른 채 취소를 다시 보내지 않고 실패로 기록한다.")
    void process_retryLookupFailed_RecordFailureWithoutCancel() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(RECLAIMED));
        given(paymentGateway.getPayment("pg-id")).willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        given(refundCommandService.recordFailure(eq(REFUND_ID), eq(2), anyString(), any())).willReturn(RefundStatus.FAILED);

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        verify(refundCommandService).recordFailure(
                eq(REFUND_ID), eq(2), eq(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage()), any());
    }

    @Test
    @DisplayName("PG를 다시 조회해 취소된 누적 금액으로 환불 상태를 맞추고 그 결과를 반환한다.")
    void syncWithPg_cancelled_SyncWithCancelledAmount() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(new PaymentGatewayResponse(
                "pg-id", GatewayPaymentStatus.PARTIAL_CANCELLED, 50000L, 20000L, Instant.now(), List.of())));
        given(refundCommandService.syncWithPgCancelledAmount(eq("pg-id"), eq(20000L), any()))
                .willReturn(PaymentSyncResult.REFUND_SYNCED);

        //when
        PaymentSyncResult result = refundProcessor.syncWithPg("pg-id");

        //then
        assertEquals(PaymentSyncResult.REFUND_SYNCED, result);
    }

    @Test
    @DisplayName("PG 응답에 취소 금액이 없으면 0으로 보고 환불 상태를 맞춘다.")
    void syncWithPg_noCancelledAmount_SyncWithZero() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(new PaymentGatewayResponse(
                "pg-id", GatewayPaymentStatus.PAID, 50000L, null, Instant.now(), List.of())));
        given(refundCommandService.syncWithPgCancelledAmount(eq("pg-id"), eq(0L), any()))
                .willReturn(PaymentSyncResult.ALREADY_DONE);

        //when
        PaymentSyncResult result = refundProcessor.syncWithPg("pg-id");

        //then
        assertEquals(PaymentSyncResult.ALREADY_DONE, result);
    }

    @Test
    @DisplayName("PG에 결제가 없으면 재시도할 수 있도록 예외를 던지고 환불 상태를 바꾸지 않는다.")
    void syncWithPg_pgPaymentNotFound_Fail() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> refundProcessor.syncWithPg("pg-id"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND.getMessage());
        verify(refundCommandService, never()).syncWithPgCancelledAmount(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소 내역이 완료됐으면 웹훅에서 바로 완료로 기록하고 금액으로 맞추지 않는다.")
    void syncWithPg_pgPendingSucceeded_RefundSynced() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L,
                cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED))));
        given(refundCommandService.findPgPendingByPgId("pg-id")).willReturn(Optional.of(PENDING_WITH_ID));

        //when
        PaymentSyncResult result = refundProcessor.syncWithPg("pg-id");

        //then
        assertEquals(PaymentSyncResult.REFUND_SYNCED, result);
        verify(refundCommandService).recordPgCancelled(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(refundCommandService, never()).syncWithPgCancelledAmount(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소가 아직 확정되지 않았으면 REFUND_PENDING을 반환하고 금액으로 맞추지 않는다.")
    void syncWithPg_pgPendingStillRequested_RefundPending() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L,
                cancellation("cancel-1", GatewayCancellationStatus.REQUESTED))));
        given(refundCommandService.findPgPendingByPgId("pg-id")).willReturn(Optional.of(PENDING_WITH_ID));

        //when
        PaymentSyncResult result = refundProcessor.syncWithPg("pg-id");

        //then
        assertEquals(PaymentSyncResult.REFUND_PENDING, result);
        verify(refundCommandService, never()).recordPgCancelled(anyLong(), any(), any());
        verify(refundCommandService, never()).reschedulePgCheck(anyLong(), any(), any());
        verify(refundCommandService, never()).syncWithPgCancelledAmount(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소가 실패했으면 실패로 기록한 뒤 취소 누적 금액으로 이어서 맞춘다.")
    void syncWithPg_pgPendingFailed_RecordFailureAndSyncAmount() {
        //given
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null,
                cancellation("cancel-1", GatewayCancellationStatus.FAILED))));
        given(refundCommandService.findPgPendingByPgId("pg-id")).willReturn(Optional.of(PENDING_WITH_ID));
        given(refundCommandService.recordPgCancelFailed(eq(REFUND_ID), eq(PENDING_SINCE), anyString(), any()))
                .willReturn(RefundStatus.FAILED);
        given(refundCommandService.syncWithPgCancelledAmount(eq("pg-id"), eq(0L), any()))
                .willReturn(PaymentSyncResult.ALREADY_DONE);

        //when
        PaymentSyncResult result = refundProcessor.syncWithPg("pg-id");

        //then
        assertEquals(PaymentSyncResult.ALREADY_DONE, result);
        verify(refundCommandService).recordPgCancelFailed(eq(REFUND_ID), eq(PENDING_SINCE), anyString(), any());
    }

    @Test
    @DisplayName("PG 취소 성공 후 완료 기록에 실패해도 실패로 기록하지 않는다(재시도하면 취소를 다시 보내므로).")
    void process_completeRecordFailed_NotRecordFailure() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(refundCommandService).recordComplete(eq(REFUND_ID), eq(1), any());

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
        verify(refundCommandService, never()).recordFailure(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("PG 취소 성공 후 완료 기록에 실패하면 조회로 완료되도록 취소 내역 ID와 함께 확인 대기로 기록한다.")
    void process_completeRecordFailed_RecordPgPending() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(refundCommandService).recordComplete(eq(REFUND_ID), eq(1), any());

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordPgPending(eq(REFUND_ID), eq(1), eq("cancel-1"), any());
    }

    @Test
    @DisplayName("완료 기록 중 다른 처리가 먼저 기록했으면 실패로 기록하지 않고 건너뛴다.")
    void process_completedByOther_Skip() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        given(paymentGateway.cancelPayment("pg-id", "단순 변심", 20000L, 50000L))
                .willReturn(cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED));
        willThrow(new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION))
                .given(refundCommandService).recordComplete(eq(REFUND_ID), eq(1), any());

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
        verify(refundCommandService, never()).recordFailure(anyLong(), anyInt(), any(), any());
        verify(refundCommandService, never()).recordPgPending(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소 내역이 완료됐으면 PG 취소 완료로 기록한다.")
    void confirmPending_succeeded_RecordPgCancelled() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITH_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L,
                cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED))));

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).recordPgCancelled(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(paymentGateway, never()).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소 내역이 실패했으면 취소를 다시 보낼 수 있도록 실패로 기록한다.")
    void confirmPending_failed_RecordPgCancelFailed() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITH_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null,
                cancellation("cancel-1", GatewayCancellationStatus.FAILED))));
        given(refundCommandService.recordPgCancelFailed(eq(REFUND_ID), eq(PENDING_SINCE), anyString(), any()))
                .willReturn(RefundStatus.FAILED);

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).recordPgCancelFailed(eq(REFUND_ID), eq(PENDING_SINCE), anyString(), any());
    }

    @Test
    @DisplayName("확인 대기 환불의 취소가 아직 확정되지 않았으면 다음 확인을 예약한다.")
    void confirmPending_stillRequested_Reschedule() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITH_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null,
                cancellation("cancel-1", GatewayCancellationStatus.REQUESTED))));
        given(refundCommandService.reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any()))
                .willReturn(RefundStatus.PG_PENDING);

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(refundCommandService, never()).recordPgCancelled(anyLong(), any(), any());
    }

    @Test
    @DisplayName("확인 중 PG 조회에 실패하면 실패로 보내지 않고 다음 확인을 예약한다.")
    void confirmPending_lookupFailed_Reschedule() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITH_ID));
        given(paymentGateway.getPayment("pg-id")).willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        given(refundCommandService.reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any()))
                .willReturn(RefundStatus.PG_PENDING);

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(refundCommandService, never()).recordPgCancelFailed(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("취소 내역 ID가 없으면 PG 취소 누적 금액에 이 환불이 반영됐을 때 완료로 기록한다.")
    void confirmPending_withoutIdApplied_RecordPgCancelled() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITHOUT_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L)));

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).recordPgCancelled(eq(REFUND_ID), eq(PENDING_SINCE), any());
    }

    @Test
    @DisplayName("취소 내역 ID가 없고 금액이 같은 접수된 취소가 있으면 누적 금액과 관계없이 확정하지 않고 다음 확인을 예약한다.")
    void confirmPending_withoutIdRequested_Reschedule() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITHOUT_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L,
                new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, 20000L))));
        given(refundCommandService.reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any()))
                .willReturn(RefundStatus.PG_PENDING);

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(refundCommandService, never()).recordPgCancelled(anyLong(), any(), any());
    }

    @Test
    @DisplayName("취소 내역 ID가 없고 PG 취소 누적 금액에 반영되지 않았으면 실패로 보내지 않고 다음 확인을 예약한다.")
    void confirmPending_withoutIdNotApplied_Reschedule() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITHOUT_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(null)));
        given(refundCommandService.reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any()))
                .willReturn(RefundStatus.PG_PENDING);

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verify(refundCommandService).reschedulePgCheck(eq(REFUND_ID), eq(PENDING_SINCE), any());
        verify(refundCommandService, never()).recordPgCancelFailed(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("확인 결과를 기록하려는 사이 다른 처리가 먼저 기록했으면 예외를 던지지 않고 건너뛴다.")
    void confirmPending_recordedByOther_Skip() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.of(PENDING_WITH_ID));
        given(paymentGateway.getPayment("pg-id")).willReturn(Optional.of(pgPayment(20000L,
                cancellation("cancel-1", GatewayCancellationStatus.SUCCEEDED))));
        willThrow(new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION))
                .given(refundCommandService).recordPgCancelled(eq(REFUND_ID), eq(PENDING_SINCE), any());

        //when & then
        assertThatCode(() -> refundProcessor.confirmPending(REFUND_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("확인할 차례가 아니면 PG를 조회하지 않는다.")
    void confirmPending_notCheckable_Skip() {
        //given
        given(refundCommandService.findPgCheckable(eq(REFUND_ID), any())).willReturn(Optional.empty());

        //when
        refundProcessor.confirmPending(REFUND_ID);

        //then
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("즉시 시도에서 예기치 못한 오류가 나도 밖으로 던지지 않는다.")
    void processById_unexpectedError_Swallowed() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any()))
                .willThrow(new DataAccessResourceFailureException("db down"));

        //when & then
        assertThatCode(() -> refundProcessor.processById(REFUND_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("배치 처리 중 한 환불에서 오류가 나도 나머지 환불을 계속 처리한다.")
    void processPending_oneFails_ContinueOthers() {
        //given
        given(refundCommandService.findProcessableIds(any(), anyInt())).willReturn(List.of(1L, 2L));
        given(refundCommandService.claim(eq(1L), any())).willThrow(new DataAccessResourceFailureException("db down"));
        given(refundCommandService.claim(eq(2L), any())).willReturn(Optional.empty());

        //when
        refundProcessor.processPending(20);

        //then
        verify(refundCommandService).claim(eq(2L), any());
    }

    @Test
    @DisplayName("스케줄러는 처리 대상 다음에 PG 취소 결과를 확인할 환불도 확인한다.")
    void processPending_pgCheckable_Confirm() {
        //given
        given(refundCommandService.findProcessableIds(any(), anyInt())).willReturn(List.of());
        given(refundCommandService.findPgCheckableIds(any(), anyInt())).willReturn(List.of(3L));
        given(refundCommandService.findPgCheckable(eq(3L), any())).willReturn(Optional.empty());

        //when
        refundProcessor.processPending(20);

        //then
        verify(refundCommandService).findPgCheckable(eq(3L), any());
        verify(refundCommandService, never()).claim(anyLong(), any());
    }

    private static PaymentCancellationResponse cancellation(String cancellationId, GatewayCancellationStatus status) {
        return new PaymentCancellationResponse(cancellationId, status);
    }

    private static PaymentGatewayResponse pgPayment(Long cancelledAmount, PaymentCancellationResponse... cancellations) {
        return new PaymentGatewayResponse("pg-id", GatewayPaymentStatus.PAID, 50000L, cancelledAmount,
                Instant.parse("2026-10-24T01:00:00Z"), List.of(cancellations));
    }
}
