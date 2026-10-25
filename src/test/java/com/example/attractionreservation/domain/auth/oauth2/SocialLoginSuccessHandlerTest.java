package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.auth.service.SocialLoginService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SocialLoginSuccessHandlerTest {

    @Mock
    private SocialLoginService socialLoginService;

    private SocialLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SocialLoginSuccessHandler(
                new SocialUserInfoMapper(),
                socialLoginService,
                new SocialLoginFailureHandler(
                        "http://localhost:3000/login"
                ),
                "http://localhost:3000/oauth2/callback"
        );
    }

    @Test
    @DisplayName("서비스가 발급한 코드를 전달하고 임시 세션을 제거한다")
    void success_redirectsWithCode() throws Exception {
        when(socialLoginService.login(any()))
                .thenReturn("test-login-code");

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var session = new MockHttpSession();
        request.setSession(session);

        handler.onAuthenticationSuccess(
                request, response, googleAuthentication()
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/oauth2/callback?code=test-login-code"
        );
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy"))
                .isEqualTo("no-referrer");

        verify(socialLoginService).login(any());
    }

    @Test
    @DisplayName("서비스의 회원 검증 오류를 실패 주소로 전달한다")
    void memberRejected_redirectsToFailure() throws Exception {
        when(socialLoginService.login(any()))
                .thenThrow(SocialLoginError.USER_NOT_ACTIVE.toException());

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                request, response, googleAuthentication()
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/login?error=USER_NOT_ACTIVE"
        );
    }

    @Test
    @DisplayName("서비스 처리 중 예외가 발생하면 공통 실패 주소로 이동한다")
    void serviceFailed_redirectsToFailure() throws Exception {
        when(socialLoginService.login(any()))
                .thenThrow(new IllegalStateException("test-storage-failure"));

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                request, response, googleAuthentication()
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/login?error=AUTHENTICATION_FAILED"
        );
    }

    private OAuth2AuthenticationToken googleAuthentication() {
        // 제공자 인증 완료 상황을 재현하며 실제 구글 서버에는 접속하지 않는다.
        var authorities = List.of(
                new SimpleGrantedAuthority("OAUTH2_USER")
        );

        var user = new DefaultOAuth2User(
                authorities,
                Map.of(
                        "sub", "google-123",
                        "email", "user@example.com",
                        "email_verified", true,
                        "name", "회원"
                ),
                "sub"
        );

        return new OAuth2AuthenticationToken(
                user, authorities, "google"
        );
    }
}