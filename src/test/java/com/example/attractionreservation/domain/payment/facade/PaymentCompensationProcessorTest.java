package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
            new ClaimedCompensation(COMPENSATION_ID, PG_ID, 9999L, "결제 금액 불일치");

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

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), any());
        verify(paymentCompensationService, never()).recordFailure(anyLong(), any(), any());
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
        verify(paymentCompensationService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("PG 취소가 실패하면 실패로 기록하고 완료로 기록하지 않는다.")
    void process_cancelFailed_RecordFailure() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        given(paymentCompensationService.recordFailure(eq(COMPENSATION_ID), any(), any()))
                .willReturn(CompensationStatus.FAILED);

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService).recordFailure(
                eq(COMPENSATION_ID), eq(ErrorCode.PAYMENT_CANCELLATION_FAILED.getMessage()), any());
        verify(paymentCompensationService, never()).recordDone(anyLong(), any());
    }

    @Test
    @DisplayName("다른 처리가 먼저 결과를 기록해 상태 전이가 거부되면 실패로 기록하지 않고 건너뛴다.")
    void process_alreadyRecordedByOther_Skip() {
        //given
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));
        willThrow(new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION))
                .given(paymentCompensationService).recordDone(eq(COMPENSATION_ID), any());

        //when
        paymentCompensationProcessor.process(COMPENSATION_ID);

        //then
        verify(paymentCompensationService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("즉시 시도는 pgId로 보상 요청을 찾아 처리한다.")
    void processByPgId_exists_Process() {
        //given
        given(paymentCompensationService.findIdByPgId(PG_ID)).willReturn(Optional.of(COMPENSATION_ID));
        given(paymentCompensationService.claim(eq(COMPENSATION_ID), any())).willReturn(Optional.of(CLAIMED));

        //when
        paymentCompensationProcessor.processByPgId(PG_ID);

        //then
        verify(paymentGateway).cancelPayment(PG_ID, "결제 금액 불일치", null);
        verify(paymentCompensationService).recordDone(eq(COMPENSATION_ID), any());
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
}
