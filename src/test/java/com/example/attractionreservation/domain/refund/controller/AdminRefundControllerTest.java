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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 관리자 Controller와 Security filter chain을 사용한다.
// 인증 주체는 Long ID로 주입하며 JWT 발급·파싱 자체는 이 테스트의 범위에서 제외한다.
@WebMvcTest(AdminRefundController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class})
@EnableWebSecurity
@ExtendWith(OutputCaptureExtension.class)
class AdminRefundControllerTest {

    private static final Long ADMIN_ID = 99L;
    private static final String PG_ID = "pg-id";
    private static final String FULL_URL = "/api/admin/payments/pg-id/refunds";
    private static final String PARTIAL_URL = FULL_URL + "/partial";
    private static final String REASON = "관리자 환불 사유";
    private static final String FULL_BODY = "{\"reason\":\"관리자 환불 사유\"}";
    private static final String PARTIAL_BODY = "{\"ticketIds\":[1,2],\"reason\":\"관리자 환불 사유\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefundFacade refundFacade;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"COMPLETED", "FAILED", "REQUESTED", "PG_PENDING"})
    @DisplayName("관리자 전체 환불은 실제 처리 상태를 응답하고 관리자와 환불 대상을 로그에 남긴다")
    void createAdminRefund_AdminRole_ReturnsActualStatusAndLogs(RefundStatus refundStatus, CapturedOutput output)
            throws Exception {
        // given
        given(refundFacade.createAdminFullRefund(eq(PG_ID), any(CreateRefundRequest.class)))
                .willReturn(new RefundResponse(10L, 40000, refundStatus));

        // when & then
        mockMvc.perform(post(FULL_URL).with(authenticatedRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("환불 요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.refundId").value(10))
                .andExpect(jsonPath("$.data.refundAmount").value(40000))
                .andExpect(jsonPath("$.data.status").value(refundStatus.name()));
        verify(refundFacade).createAdminFullRefund(PG_ID, new CreateRefundRequest(REASON));
        assertThat(output.getAll().lines().filter(line -> line.contains("event=REFUND_ADMIN_FULL_REQUESTED")))
                .singleElement().asString()
                .contains("refundId=10 userId=99 pgId=pg-id amount=40000 status=" + refundStatus.name())
                .doesNotContain(REASON);
    }

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = {"COMPLETED", "FAILED", "REQUESTED", "PG_PENDING"})
    @DisplayName("관리자 부분 환불은 선택 티켓을 전달하고 실제 처리 상태와 관리자 로그를 남긴다")
    void createAdminPartialRefund_AdminRole_ReturnsActualStatusAndLogs(RefundStatus refundStatus, CapturedOutput output)
            throws Exception {
        // given
        given(refundFacade.createAdminPartialRefund(eq(PG_ID), any(CreatePartialRefundRequest.class)))
                .willReturn(new RefundResponse(11L, 20000, refundStatus));

        // when & then
        mockMvc.perform(post(PARTIAL_URL).with(authenticatedRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(PARTIAL_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("부분 환불 요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.refundId").value(11))
                .andExpect(jsonPath("$.data.refundAmount").value(20000))
                .andExpect(jsonPath("$.data.status").value(refundStatus.name()));
        verify(refundFacade).createAdminPartialRefund(PG_ID, new CreatePartialRefundRequest(List.of(1L, 2L), REASON));
        assertThat(output.getAll().lines().filter(line -> line.contains("event=REFUND_ADMIN_PARTIAL_REQUESTED")))
                .singleElement().asString()
                .contains("refundId=11 userId=99 pgId=pg-id amount=20000 status=" + refundStatus.name())
                .doesNotContain(REASON);
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    @DisplayName("미인증 사용자의 관리자 전체·부분 환불 요청은 401로 거부한다")
    void adminRefund_Unauthenticated_ReturnsUnauthorized(String url, String body) throws Exception {
        // given
        // 인증 정보 없이 요청한다.

        // when & then
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @ParameterizedTest
    @MethodSource("validRequests")
    @DisplayName("일반 사용자의 관리자 전체·부분 환불 요청은 403으로 거부한다")
    void adminRefund_UserRole_ReturnsForbidden(String url, String body) throws Exception {
        // given
        // 일반 사용자 권한으로 요청한다.

        // when & then
        mockMvc.perform(post(url).with(authenticatedRole("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    @DisplayName("관리자 환불도 사유·티켓 목록이 잘못되면 400을 반환하고 Facade를 호출하지 않는다")
    void adminRefund_InvalidRequest_ReturnsBadRequest(String url, String body) throws Exception {
        // given
        // 요청 본문의 필수 값과 길이 제한을 검증한다.

        // when & then
        mockMvc.perform(post(url).with(authenticatedRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(refundFacade);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"PAYMENT_NOT_FOUND", "REFUND_INVALID_QUANTITY"})
    @DisplayName("관리자 전체 환불의 업무 예외는 기존 HTTP 상태와 공통 오류 응답으로 반환한다")
    void createAdminRefund_BusinessException_ReturnsExpectedError(ErrorCode errorCode) throws Exception {
        // given
        given(refundFacade.createAdminFullRefund(eq(PG_ID), any(CreateRefundRequest.class)))
                .willThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(post(FULL_URL).with(authenticatedRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_BODY))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"REFUND_DUPLICATE_TICKET", "REFUND_INVALID_TICKET", "TICKET_NOT_REFUNDABLE"})
    @DisplayName("관리자 부분 환불의 티켓 검증 오류는 기존 HTTP 상태와 공통 오류 응답으로 반환한다")
    void createAdminPartialRefund_BusinessException_ReturnsExpectedError(ErrorCode errorCode) throws Exception {
        // given
        given(refundFacade.createAdminPartialRefund(eq(PG_ID), any(CreatePartialRefundRequest.class)))
                .willThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(post(PARTIAL_URL).with(authenticatedRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(PARTIAL_BODY))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    private static Stream<Arguments> validRequests() {
        return Stream.of(Arguments.of(FULL_URL, FULL_BODY), Arguments.of(PARTIAL_URL, PARTIAL_BODY));
    }

    private static Stream<Arguments> invalidRequests() {
        String tooLongReason = "a".repeat(201);
        return Stream.of(
                Arguments.of(FULL_URL, "{}"),
                Arguments.of(FULL_URL, "{\"reason\":\" \"}"),
                Arguments.of(FULL_URL, "{\"reason\":\"" + tooLongReason + "\"}"),
                Arguments.of(PARTIAL_URL, "{\"reason\":\"사유\"}"),
                Arguments.of(PARTIAL_URL, "{\"ticketIds\":[],\"reason\":\"사유\"}"),
                Arguments.of(PARTIAL_URL, "{\"ticketIds\":[null],\"reason\":\"사유\"}"),
                Arguments.of(PARTIAL_URL, "{\"ticketIds\":[1]}"),
                Arguments.of(PARTIAL_URL, "{\"ticketIds\":[1],\"reason\":\" \"}"),
                Arguments.of(PARTIAL_URL, "{\"ticketIds\":[1],\"reason\":\"" + tooLongReason + "\"}")
        );
    }

    private RequestPostProcessor authenticatedRole(String role) {
        return authentication(new UsernamePasswordAuthenticationToken(
                ADMIN_ID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }
}
