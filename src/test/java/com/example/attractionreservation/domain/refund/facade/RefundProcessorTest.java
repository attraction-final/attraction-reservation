package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.payment.port.PaymentGateway;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
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
    private static final ClaimedRefund CLAIMED =
            new ClaimedRefund(REFUND_ID, "pg-id", 20000L, "단순 변심", 50000L);

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

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(paymentGateway).cancelPayment("pg-id", "단순 변심", 20000L, 50000L);
        verify(refundCommandService).recordComplete(eq(REFUND_ID), any());
        verify(refundCommandService, never()).recordFailure(anyLong(), any(), any());
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
        given(refundCommandService.recordFailure(eq(REFUND_ID), anyString(), any())).willReturn(RefundStatus.FAILED);

        //when
        refundProcessor.process(REFUND_ID);

        //then
        verify(refundCommandService).recordFailure(
                eq(REFUND_ID), eq(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage()), any());
        verify(refundCommandService, never()).recordComplete(anyLong(), any());
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
        verify(refundCommandService).recordDead(REFUND_ID, errorCode.getMessage());
        verify(refundCommandService, never()).recordFailure(anyLong(), any(), any());
        verify(refundCommandService, never()).recordComplete(anyLong(), any());
    }

    @Test
    @DisplayName("PG 취소 성공 후 완료 기록에 실패해도 실패로 기록하지 않는다(재시도하면 취소를 다시 보내므로).")
    void process_completeRecordFailed_NotRecordFailure() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new DataAccessResourceFailureException("db down"))
                .given(refundCommandService).recordComplete(eq(REFUND_ID), any());

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
        verify(refundCommandService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("완료 기록 중 다른 처리가 먼저 기록했으면 실패로 기록하지 않고 건너뛴다.")
    void process_completedByOther_Skip() {
        //given
        given(refundCommandService.claim(eq(REFUND_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION))
                .given(refundCommandService).recordComplete(eq(REFUND_ID), any());

        //when & then
        assertThatCode(() -> refundProcessor.process(REFUND_ID)).doesNotThrowAnyException();
        verify(refundCommandService, never()).recordFailure(anyLong(), any(), any());
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
}
