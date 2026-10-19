package com.example.attractionreservation.domain.payment.controller;

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

    private PageableDto<PaymentResponse> emptyPage() {
        return new PageableDto<>(List.of(), 0, 10, 0, 0, false);
    }
}