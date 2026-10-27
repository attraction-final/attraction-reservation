package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.domain.refund.dto.CreatePartialRefundRequest;
import com.example.attractionreservation.domain.refund.dto.CreateRefundRequest;
import com.example.attractionreservation.domain.refund.dto.RefundItemRequest;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RefundFacadeTest {

    private static final Long USER_ID = 1L;
    private static final String PG_ID = "pg-id";

    @Mock
    private RefundService refundService;

    @Mock
    private RefundProcessor refundProcessor;

    @InjectMocks
    private RefundFacade refundFacade;

    @Test
    @DisplayName("전체 환불을 저장한 뒤 PG 취소를 즉시 한 번 시도하고, 처리 후 환불 상태를 반환한다.")
    void createRefund_saved_ProcessThenReturnStatus() {
        //given
        RefundResponse completed = new RefundResponse(1L, 40000, RefundStatus.COMPLETED);
        given(refundService.createFullRefund(eq(USER_ID), eq(PG_ID), eq("단순 변심"), any())).willReturn(1L);
        given(refundService.findRefundById(1L)).willReturn(completed);

        //when
        RefundResponse response = refundFacade.createRefund(USER_ID, PG_ID, new CreateRefundRequest("단순 변심"));

        //then
        assertThat(response).isEqualTo(completed);
        InOrder inOrder = inOrder(refundService, refundProcessor);
        inOrder.verify(refundService).createFullRefund(eq(USER_ID), eq(PG_ID), eq("단순 변심"), any());
        inOrder.verify(refundProcessor).processById(1L);
        inOrder.verify(refundService).findRefundById(1L);
    }

    @Test
    @DisplayName("부분 환불은 상품별 환불 수량으로 저장한 뒤 PG 취소를 즉시 한 번 시도하고, 처리 후 환불 상태를 반환한다.")
    void createPartialRefund_saved_ProcessThenReturnStatus() {
        //given
        CreatePartialRefundRequest request = new CreatePartialRefundRequest(
                List.of(new RefundItemRequest(100L, 2), new RefundItemRequest(200L, 1)), "단순 변심");
        RefundResponse failed = new RefundResponse(1L, 25000, RefundStatus.FAILED);
        given(refundService.createRefund(eq(USER_ID), eq(PG_ID), eq(Map.of(100L, 2, 200L, 1)), eq("단순 변심"), any()))
                .willReturn(1L);
        given(refundService.findRefundById(1L)).willReturn(failed);

        //when
        RefundResponse response = refundFacade.createPartialRefund(USER_ID, PG_ID, request);

        //then
        assertThat(response).isEqualTo(failed);
        InOrder inOrder = inOrder(refundService, refundProcessor);
        inOrder.verify(refundService).createRefund(eq(USER_ID), eq(PG_ID), any(), eq("단순 변심"), any());
        inOrder.verify(refundProcessor).processById(1L);
        inOrder.verify(refundService).findRefundById(1L);
    }

    @Test
    @DisplayName("전체 환불 저장에 실패하면 PG 취소를 시도하지 않고 예외를 그대로 던진다.")
    void createRefund_saveFailed_NotProcessed() {
        //given
        given(refundService.createFullRefund(eq(USER_ID), eq(PG_ID), eq("단순 변심"), any()))
                .willThrow(new BusinessException(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT));

        //when & then
        assertThatThrownBy(() -> refundFacade.createRefund(USER_ID, PG_ID, new CreateRefundRequest("단순 변심")))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT.getMessage());
        verifyNoInteractions(refundProcessor);
        verify(refundService, never()).findRefundById(any());
    }

    @Test
    @DisplayName("부분 환불 저장에 실패하면 PG 취소를 시도하지 않고 예외를 그대로 던진다.")
    void createPartialRefund_saveFailed_NotProcessed() {
        //given
        CreatePartialRefundRequest request = new CreatePartialRefundRequest(
                List.of(new RefundItemRequest(100L, 5)), "단순 변심");
        given(refundService.createRefund(eq(USER_ID), eq(PG_ID), eq(Map.of(100L, 5)), eq("단순 변심"), any()))
                .willThrow(new BusinessException(ErrorCode.REFUND_INVALID_QUANTITY));

        //when & then
        assertThatThrownBy(() -> refundFacade.createPartialRefund(USER_ID, PG_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
        verifyNoInteractions(refundProcessor);
        verify(refundService, never()).findRefundById(any());
    }

    @Test
    @DisplayName("같은 주문 상품이 중복되면 환불을 저장하지 않고 거절한다.")
    void createPartialRefund_duplicateOrderItem_Fail() {
        //given
        CreatePartialRefundRequest request = new CreatePartialRefundRequest(
                List.of(new RefundItemRequest(100L, 1), new RefundItemRequest(100L, 1)), "단순 변심");

        //when & then
        assertThatThrownBy(() -> refundFacade.createPartialRefund(USER_ID, PG_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_DUPLICATE_ORDER_ITEM.getMessage());
        verifyNoInteractions(refundService, refundProcessor);
    }
}
