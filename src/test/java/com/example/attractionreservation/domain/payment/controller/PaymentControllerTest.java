package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.facade.PaymentFacade;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORDER_ID = 10L;
    private static final String PG_ID = "pg-id";
    private static final String CONFIRM_URL = "/api/payments/{pgId}/confirm";
    private static final String REQUEST_BODY = """
            {"orderId": 10}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentFacade paymentFacade;

    @Test
    @DisplayName("결제 승인에 성공하면 200과 승인된 결제 정보를 반환한다.")
    void confirmPayment_success_Ok() throws Exception {
        //given
        ConfirmPaymentResponse response = new ConfirmPaymentResponse(
                1L, ORDER_ID, PG_ID, 10000, PaymentStatus.PAID, Instant.parse("2026-10-20T01:00:00Z"));
        given(paymentFacade.confirmPayment(USER_ID, PG_ID, new ConfirmPaymentRequest(ORDER_ID))).willReturn(response);

        //when & then
        mockMvc.perform(confirmRequest(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pgId").value(PG_ID))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.status").value(PaymentStatus.PAID.name()));
    }

    @Test
    @DisplayName("다른 사용자의 결제거나 없는 결제면 404를 반환한다.")
    void confirmPayment_notFound_NotFound() throws Exception {
        //given
        given(paymentFacade.confirmPayment(any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        //when & then
        mockMvc.perform(confirmRequest(REQUEST_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("결제사 조회에 실패하면 502를 반환한다.")
    void confirmPayment_gatewayError_BadGateway() throws Exception {
        //given
        given(paymentFacade.confirmPayment(any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));

        //when & then
        mockMvc.perform(confirmRequest(REQUEST_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage()));
    }

    @Test
    @DisplayName("userId가 없으면 400을 반환한다.")
    void confirmPayment_missingUserId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(post(CONFIRM_URL, PG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(paymentFacade);
    }

    @Test
    @DisplayName("요청 본문에 orderId가 없으면 400을 반환한다.")
    void confirmPayment_missingOrderId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(confirmRequest("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(paymentFacade);
    }

    private RequestBuilder confirmRequest(String body) {
        return post(CONFIRM_URL, PG_ID)
                .param("userId", String.valueOf(USER_ID))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
