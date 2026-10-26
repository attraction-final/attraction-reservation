package com.example.attractionreservation.infra.portone.webhook;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.payment.service.WebhookEventCommandService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.infra.portone.webhook.dto.PortOneWebhookEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PortOne 서버는 사용자 토큰 없이 호출하므로, 실제 보안 설정에서도 인증 없이 처리되는지 함께 검증한다.
@WebMvcTest(PortOneWebhookController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class PortOneWebhookControllerTest {

    private static final String URL = "/api/payments/webhooks/portone";
    private static final String BODY = """
            {"type":"Transaction.Paid","data":{"paymentId":"pg-id"}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PortOneWebhookVerifier portOneWebhookVerifier;

    @MockitoBean
    private WebhookEventCommandService webhookEventCommandService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("서명이 유효하면 원문 그대로 저장하고 200을 반환한다.")
    void receiveWebhook_valid_Ok() throws Exception {
        //given
        given(portOneWebhookVerifier.verify(BODY, "webhook-1", "v1,signature", "1700000000"))
                .willReturn(new PortOneWebhookEvent("WebhookTransactionPaid", "pg-id"));
        given(webhookEventCommandService.register("webhook-1", "WebhookTransactionPaid", "pg-id", BODY))
                .willReturn(true);

        //when & then
        mockMvc.perform(webhookRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(webhookEventCommandService).register("webhook-1", "WebhookTransactionPaid", "pg-id", BODY);
    }

    @Test
    @DisplayName("이미 받은 웹훅이어도 200을 반환한다.")
    void receiveWebhook_duplicate_Ok() throws Exception {
        //given
        given(portOneWebhookVerifier.verify(BODY, "webhook-1", "v1,signature", "1700000000"))
                .willReturn(new PortOneWebhookEvent("WebhookTransactionPaid", "pg-id"));
        given(webhookEventCommandService.register("webhook-1", "WebhookTransactionPaid", "pg-id", BODY))
                .willReturn(false);

        //when & then
        mockMvc.perform(webhookRequest())
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("서명이 유효하지 않으면 401을 반환하고 저장하지 않는다.")
    void receiveWebhook_invalidSignature_Unauthorized() throws Exception {
        //given
        given(portOneWebhookVerifier.verify(any(), any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE));

        //when & then
        mockMvc.perform(webhookRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                // 보안 필터의 401이 아니라 서명 검증 실패의 401인지 메시지로 구분한다.
                .andExpect(jsonPath("$.message").value(ErrorCode.PAYMENT_WEBHOOK_INVALID_SIGNATURE.getMessage()));
        verify(portOneWebhookVerifier).verify(any(), any(), any(), any());
        verifyNoInteractions(webhookEventCommandService);
    }

    private RequestBuilder webhookRequest() {
        return post(URL)
                .contentType(MediaType.APPLICATION_JSON)
                .header("webhook-id", "webhook-1")
                .header("webhook-signature", "v1,signature")
                .header("webhook-timestamp", "1700000000")
                .content(BODY);
    }
}
