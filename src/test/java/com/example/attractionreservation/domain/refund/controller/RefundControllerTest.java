package com.example.attractionreservation.domain.refund.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.refund.dto.CreatePartialRefundRequest;
import com.example.attractionreservation.domain.refund.dto.CreateRefundRequest;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.facade.RefundFacade;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RefundController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class RefundControllerTest {

    private static final Long USER_ID = 1L;
    private static final String REFUND_URL = "/api/payments/{pgId}/refunds";
    private static final String PARTIAL_REFUND_URL = "/api/payments/{pgId}/refunds/partial";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundFacade refundFacade;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("전체 환불을 요청하면 200과 환불 ID·금액·상태를 반환한다.")
    void createRefund_valid_Success() throws Exception {
        //given
        given(refundFacade.createRefund(eq(USER_ID), eq("pg-id"), any(CreateRefundRequest.class)))
                .willReturn(new RefundResponse(10L, 40000, RefundStatus.COMPLETED));

        //when & then
        mockMvc.perform(post(REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "단순 변심" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("환불 요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.refundId").value(10))
                .andExpect(jsonPath("$.data.refundAmount").value(40000))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{ \"reason\": \" \" }"})
    @DisplayName("전체 환불 사유가 없으면 400을 반환하고 환불하지 않는다.")
    void createRefund_blankReason_BadRequest(String body) throws Exception {
        //when & then
        mockMvc.perform(post(REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("환불 사유가 200자를 넘으면 400을 반환하고 환불하지 않는다.")
    void createRefund_tooLongReason_BadRequest() throws Exception {
        //when & then
        mockMvc.perform(post(REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"" + "a".repeat(201) + "\" }"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("인증 없이 전체 환불을 요청하면 401을 반환하고 환불하지 않는다.")
    void createRefund_unauthenticated_Unauthorized() throws Exception {
        //when & then
        mockMvc.perform(post(REFUND_URL, "pg-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "단순 변심" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("환불할 수 없는 결제면 409를 반환한다.")
    void createRefund_notRefundablePayment_Conflict() throws Exception {
        //given
        given(refundFacade.createRefund(eq(USER_ID), eq("pg-id"), any(CreateRefundRequest.class)))
                .willThrow(new BusinessException(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT));

        //when & then
        mockMvc.perform(post(REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "reason": "단순 변심" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT.getMessage()));
    }

    @Test
    @DisplayName("부분 환불을 요청하면 200과 환불 결과를 반환한다.")
    void createPartialRefund_valid_Success() throws Exception {
        //given
        given(refundFacade.createPartialRefund(eq(USER_ID), eq("pg-id"), any(CreatePartialRefundRequest.class)))
                .willReturn(new RefundResponse(10L, 20000, RefundStatus.FAILED));

        //when & then
        mockMvc.perform(post(PARTIAL_REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 100, "quantity": 2 } ], "reason": "단순 변심" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("부분 환불 요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.refundId").value(10))
                .andExpect(jsonPath("$.data.refundAmount").value(20000))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ \"reason\": \"단순 변심\" }",
            "{ \"items\": [], \"reason\": \"단순 변심\" }",
            "{ \"items\": [ null ], \"reason\": \"단순 변심\" }",
            "{ \"items\": [ { \"orderItemId\": 100, \"quantity\": 0 } ], \"reason\": \"단순 변심\" }",
            "{ \"items\": [ { \"orderItemId\": 100 } ], \"reason\": \"단순 변심\" }",
            "{ \"items\": [ { \"quantity\": 1 } ], \"reason\": \"단순 변심\" }",
            "{ \"items\": [ { \"orderItemId\": 100, \"quantity\": 1 } ] }"
    })
    @DisplayName("부분 환불 항목이 없거나, null 항목이 있거나, 수량이 1 미만이거나, 필수값이 빠지면 400을 반환하고 환불하지 않는다.")
    void createPartialRefund_invalidRequest_BadRequest(String body) throws Exception {
        //when & then
        mockMvc.perform(post(PARTIAL_REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @Test
    @DisplayName("같은 주문 상품이 중복되면 400을 반환한다.")
    void createPartialRefund_duplicateOrderItem_BadRequest() throws Exception {
        //given
        given(refundFacade.createPartialRefund(eq(USER_ID), eq("pg-id"), any(CreatePartialRefundRequest.class)))
                .willThrow(new BusinessException(ErrorCode.REFUND_DUPLICATE_ORDER_ITEM));

        //when & then
        mockMvc.perform(post(PARTIAL_REFUND_URL, "pg-id")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 100, "quantity": 1 }, { "orderItemId": 100, "quantity": 1 } ],
                                  "reason": "단순 변심" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.REFUND_DUPLICATE_ORDER_ITEM.getMessage()));
    }

    @Test
    @DisplayName("인증 없이 부분 환불을 요청하면 401을 반환하고 환불하지 않는다.")
    void createPartialRefund_unauthenticated_Unauthorized() throws Exception {
        //when & then
        mockMvc.perform(post(PARTIAL_REFUND_URL, "pg-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "orderItemId": 100, "quantity": 1 } ], "reason": "단순 변심" }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                USER_ID,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }
}
