package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.order.dto.OrderItemResponse;
import com.example.attractionreservation.domain.order.dto.OrderResponse;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.facade.PaymentFacade;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
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

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

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
    @DisplayName("PG에서 결제가 실패했으면 결제사 장애(502)가 아닌 409를 반환한다.")
    void confirmPayment_pgFailed_Conflict() throws Exception {
        //given
        given(paymentFacade.confirmPayment(any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_FAILED));

        //when & then
        mockMvc.perform(confirmRequest(REQUEST_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_PG_FAILED.getMessage()));
    }

    @Test
    @DisplayName("인증 없이 결제 승인을 요청하면 401을 반환한다.")
    void confirmPayment_unauthenticated_Unauthorized() throws Exception {
        //when & then
        mockMvc.perform(post(CONFIRM_URL, PG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
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
                .with(authenticatedMember())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

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
        mockMvc.perform(get("/api/payments").with(authenticatedMember()))
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
        mockMvc.perform(get("/api/payments").with(authenticatedMember()))
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
                        .with(authenticatedMember())
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
    @DisplayName("인증 없이 결제 목록을 조회하면 401을 반환한다.")
    void getAllPayments_unauthenticated_Unauthorized() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("존재하지 않는 결제 상태로 요청하면 400을 반환한다.")
    void getAllPayments_invalidStatus_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments")
                        .with(authenticatedMember())
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
                        .with(authenticatedMember())
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
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.paymentId").value(10))
                .andExpect(jsonPath("$.data.amount").value(10000))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.order.orderId").value(20))
                .andExpect(jsonPath("$.data.order.totalAmount").value(10000))
                .andExpect(jsonPath("$.data.order.status").value("COMPLETED"))
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
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).with(authenticatedMember()))
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
        mockMvc.perform(get("/api/payments/{paymentId}", 10L).with(authenticatedMember()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("결제 id가 숫자가 아니면 400을 반환한다.")
    void getPaymentById_nonNumericId_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", "abc").with(authenticatedMember()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()));
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("인증 없이 결제 단건을 조회하면 401을 반환한다.")
    void getPaymentById_unauthenticated_Unauthorized() throws Exception {
        //when & then
        mockMvc.perform(get("/api/payments/{paymentId}", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(paymentService);
    }

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                USER_ID,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }

    private PageableDto<PaymentResponse> emptyPage() {
        return new PageableDto<>(List.of(), 0, 10, 0, 0, false);
    }

    private PaymentDetailResponse paymentDetail(PaymentStatus status, Instant paidAt) {
        OrderResponse order = new OrderResponse(
                20L, 10000, OrderStatus.COMPLETED, Instant.parse("2026-10-19T00:00:00Z"),
                List.of(new OrderItemResponse(30L, 40L, "종일이용권", 5000, 2))
        );
        return new PaymentDetailResponse(10L, 10000, status, paidAt, Instant.parse("2026-10-19T00:00:00Z"), order);
    }
}
