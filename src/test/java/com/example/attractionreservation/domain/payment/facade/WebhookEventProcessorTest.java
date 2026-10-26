package com.example.attractionreservation.domain.payment.facade;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.service.WebhookEventCommandService;
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
class WebhookEventProcessorTest {

    private static final Long EVENT_ID = 1L;
    private static final String PG_ID = "pg-id";

    @Mock
    private WebhookEventCommandService webhookEventCommandService;

    @Mock
    private PaymentFacade paymentFacade;

    @InjectMocks
    private WebhookEventProcessor webhookEventProcessor;

    @Test
    @DisplayName("점유한 웹훅은 결제를 동기화하고 결과를 기록한다.")
    void process_claimed_RecordResult() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.of(PG_ID));
        given(paymentFacade.syncPayment(PG_ID)).willReturn(PaymentSyncResult.APPROVED);

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verify(webhookEventCommandService).recordResult(eq(EVENT_ID), eq(PaymentSyncResult.APPROVED), any());
        verify(webhookEventCommandService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("점유하지 못한 웹훅은 결제를 동기화하지 않는다.")
    void process_notClaimed_Skip() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.empty());

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verifyNoInteractions(paymentFacade);
        verify(webhookEventCommandService, never()).recordResult(anyLong(), any(), any());
    }

    @Test
    @DisplayName("다른 처리가 웹훅을 잠그고 있으면 건너뛴다.")
    void process_lockedByOther_Skip() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any()))
                .willThrow(new CannotAcquireLockException("lock wait timeout"));

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verifyNoInteractions(paymentFacade);
        verify(webhookEventCommandService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("결제 동기화 중 재시도가 필요한 오류가 나면 실패로 기록한다.")
    void process_syncError_RecordFailure() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.of(PG_ID));
        given(paymentFacade.syncPayment(PG_ID)).willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verify(webhookEventCommandService).recordFailure(
                eq(EVENT_ID), eq(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage()), any());
        verify(webhookEventCommandService, never()).recordResult(anyLong(), any(), any());
    }

    @Test
    @DisplayName("다른 처리가 먼저 결과를 기록해 상태 전이가 거부되면 실패로 기록하지 않고 건너뛴다.")
    void process_alreadyRecordedByOther_Skip() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.of(PG_ID));
        given(paymentFacade.syncPayment(PG_ID)).willReturn(PaymentSyncResult.APPROVED);
        willThrow(new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION))
                .given(webhookEventCommandService).recordResult(eq(EVENT_ID), eq(PaymentSyncResult.APPROVED), any());

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verify(webhookEventCommandService).recordResult(eq(EVENT_ID), eq(PaymentSyncResult.APPROVED), any());
        verify(webhookEventCommandService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("웹훅 상태 전이가 아닌 업무 예외는 실패로 기록한다.")
    void process_otherBusinessException_RecordFailure() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.of(PG_ID));
        given(paymentFacade.syncPayment(PG_ID)).willThrow(new BusinessException(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND));

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verify(webhookEventCommandService).recordFailure(
                eq(EVENT_ID), eq(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND.getMessage()), any());
    }

    @Test
    @DisplayName("수동 처리가 필요한 결과도 결과로 기록하고 실패로 기록하지 않는다.")
    void process_compensationFailed_RecordResult() {
        //given
        given(webhookEventCommandService.claim(eq(EVENT_ID), any())).willReturn(Optional.of(PG_ID));
        given(paymentFacade.syncPayment(PG_ID)).willReturn(PaymentSyncResult.COMPENSATION_FAILED);

        //when
        webhookEventProcessor.process(EVENT_ID);

        //then
        verify(webhookEventCommandService).recordResult(eq(EVENT_ID), eq(PaymentSyncResult.COMPENSATION_FAILED), any());
        verify(webhookEventCommandService, never()).recordFailure(anyLong(), any(), any());
    }

    @Test
    @DisplayName("한 웹훅 처리 중 예외가 밖으로 나와도 같은 배치의 다음 웹훅을 처리한다.")
    void processPending_oneFails_ContinueNext() {
        //given
        given(webhookEventCommandService.findProcessableIds(any(), eq(10))).willReturn(List.of(1L, 2L));
        given(webhookEventCommandService.claim(eq(1L), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_WEBHOOK_NOT_FOUND));
        given(webhookEventCommandService.claim(eq(2L), any())).willReturn(Optional.empty());

        //when
        webhookEventProcessor.processPending(10);

        //then
        verify(webhookEventCommandService).claim(eq(2L), any());
    }

    @Test
    @DisplayName("처리 대상 웹훅을 조회해 하나씩 처리한다.")
    void processPending_targets_ProcessEach() {
        //given
        given(webhookEventCommandService.findProcessableIds(any(), eq(10))).willReturn(List.of(1L, 2L));
        given(webhookEventCommandService.claim(anyLong(), any())).willReturn(Optional.empty());

        //when
        webhookEventProcessor.processPending(10);

        //then
        verify(webhookEventCommandService).claim(eq(1L), any());
        verify(webhookEventCommandService).claim(eq(2L), any());
        verify(paymentFacade, never()).syncPayment(anyString());
    }
}
