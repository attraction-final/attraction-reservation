package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.order.dto.OrderItemResponse;
import com.example.attractionreservation.domain.order.dto.OrderResponse;
import com.example.attractionreservation.domain.order.entity.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private PaymentService paymentService;

    @Test
    @DisplayName("결제 목록 조회에 성공하면 페이지 응답을 반환한다.")
    void getAllPayments_validRequest_ReturnsPage() throws Exception {
        //given
        PageableDto<PaymentResponse> page = new PageableDto<>(
                List.of(new PaymentResponse(1L, 10000, PaymentStatus.PAID, Instant.parse("2026-10-19T00:00:00Z"))),
                0, 10, 1, 1, false
        );
        given(paymentService.findAllPaymentsByUserId(eq(1L), any(SearchPaymentCondition.class), any(Pageable.class)))
                .willReturn(page);

        //when & then
        mockMvc.perform(get("/api/payments").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].paymentId").value(1))
                .andExpect(jsonPath("$.data.content[0].amount").value(10000))
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("결제 상태를 지정하지 않으면 상태 조건 없이 조회한다.")
    void getAllPayments_withoutStatus_PassesNullCondition() throws Exception {
        //given
        given(paymentService.findAllPaymentsByUserId(eq(1L), any(SearchPaymentCondition.class), any(Pageable.class)))
                .willReturn(emptyPage());
        ArgumentCaptor<SearchPaymentCondition> captor = ArgumentCaptor.forClass(SearchPaymentCondition.class);

        //when
        mockMvc.perform(get("/api/payments").param("userId", "1"))
                .andExpect(status().isOk());

        //then
        verify(paymentService).findAllPaymentsByUserId(eq(1L), captor.capture(), any(Pageable.class));
        assertNull(captor.getValue().paymentStatus());
    }

    @Test
    @DisplayName("결제 상태와 페이지·정렬 파라미터를 서비스에 전달한다.")
    void getAllPayments_withStatusAndPageParams_PassesThem() throws Exception {
        //given
        given(paymentService.findAllPaymentsByUserId(eq(1L), any(SearchPaymentCondition.class), any(Pageable.class)))
                .willReturn(emptyPage());
        ArgumentCaptor<SearchPaymentCondition> conditionCaptor = ArgumentCaptor.forClass(SearchPaymentCondition.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        //when
        mockMvc.perform(get("/api/payments")
                        .param("userId", "1")
                        .param("paymentStatus", "PAID")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "amount,asc"))
                .andExpect(status().isOk());

        //then
        verify(paymentService).findAllPaymentsByUserId(eq(1L), conditionCaptor.capture(), pageableCaptor.capture());
        assertEquals(PaymentStatus.PAID, conditionCaptor.getValue().paymentStatus());
        assertEquals(2, pageableCaptor.getValue().getPageNumber());
        assertEquals(5, pageableCaptor.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.ASC, "amount"), pageableCaptor.getValue().getSort());
    }

    @Test
    @DisplayName("userId가 없으면 400을 반환한다.")
    void getAllPayments_missingUserId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.MISSING_REQUEST_VALUE.getMessage()));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("존재하지 않는 결제 상태로 요청하면 400을 반환한다.")
    void getAllPayments_invalidStatus_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments")
                        .param("userId", "1")
                        .param("paymentStatus", "WRONG"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("지원하지 않는 정렬 조건이면 400을 반환한다.")
    void getAllPayments_unsupportedSortProperty_BadRequest() throws Exception {
        //given
        given(paymentService.findAllPaymentsByUserId(eq(1L), any(SearchPaymentCondition.class), any(Pageable.class)))
                .willThrow(new BusinessException(ErrorCode.INVALID_SORT_PROPERTY));

        //when & then
        mockMvc.perform(get("/api/payments")
                        .param("userId", "1")
                        .param("sort", "pgId,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_SORT_PROPERTY.getMessage()));
    }

    @Test
    @DisplayName("결제 단건 조회에 성공하면 결제 정보를 반환한다.")
    void getPaymentById_ownPayment_ReturnsPayment() throws Exception {
        //given
        given(paymentService.findPaymentById(1L, 10L))
                .willReturn(paymentDetail(PaymentStatus.PAID, Instant.parse("2026-10-19T00:00:00Z")));

        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(10))
                .andExpect(jsonPath("$.data.amount").value(10000))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.order.orderId").value(20))
                .andExpect(jsonPath("$.data.order.totalAmount").value(10000))
                .andExpect(jsonPath("$.data.order.status").value("PAID"))
                .andExpect(jsonPath("$.data.order.orderItems[0].orderItemId").value(30))
                .andExpect(jsonPath("$.data.order.orderItems[0].ticketProductId").value(40))
                .andExpect(jsonPath("$.data.order.orderItems[0].ticketProductName").value("종일이용권"))
                .andExpect(jsonPath("$.data.order.orderItems[0].price").value(5000))
                .andExpect(jsonPath("$.data.order.orderItems[0].quantity").value(2));
    }

    @Test
    @DisplayName("결제 대기 상태면 결제 완료 시각 없이 응답한다.")
    void getPaymentById_pendingPayment_OmitsPaidAt() throws Exception {
        //given
        given(paymentService.findPaymentById(1L, 10L))
                .willReturn(paymentDetail(PaymentStatus.PENDING, null));

        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist());
    }

    @Test
    @DisplayName("결제가 없거나 다른 사용자의 결제면 404를 반환한다.")
    void getPaymentById_notFound_NotFound() throws Exception {
        //given
        given(paymentService.findPaymentById(1L, 10L))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).param("userId", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("결제 id가 숫자가 아니면 400을 반환한다.")
    void getPaymentById_nonNumericId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", "abc").param("userId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("단건 조회 시 userId가 없으면 400을 반환한다.")
    void getPaymentById_missingUserId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", 10L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.MISSING_REQUEST_VALUE.getMessage()));
        verifyNoInteractions(paymentService);
    }

    private PageableDto<PaymentResponse> emptyPage() {
        return new PageableDto<>(List.of(), 0, 10, 0, 0, false);
    }

    private PaymentDetailResponse paymentDetail(PaymentStatus status, Instant paidAt) {
        OrderResponse order = new OrderResponse(
                20L, 10000, OrderStatus.PAID, Instant.parse("2026-10-19T00:00:00Z"),
                List.of(new OrderItemResponse(30L, 40L, "종일이용권", 5000, 2))
        );
        return new PaymentDetailResponse(10L, 10000, status, paidAt, Instant.parse("2026-10-19T00:00:00Z"), order);
    }
}
