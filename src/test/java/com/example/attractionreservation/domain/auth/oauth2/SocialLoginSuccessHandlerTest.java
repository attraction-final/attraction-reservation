package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import com.example.attractionreservation.domain.auth.service.SocialMemberResolver;
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
    private SocialMemberResolver socialMemberResolver;

    @Mock
    private SocialLoginCodeRepository loginCodeRepository;

    private SocialLoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SocialLoginSuccessHandler(
                new SocialUserInfoMapper(),
                socialMemberResolver,
                loginCodeRepository,
                new SocialLoginFailureHandler(
                        "http://localhost:3000/login"
                ),
                "http://localhost:3000/oauth2/callback"
        );
    }

    @Test
    @DisplayName("회원 확인 후 일회용 코드를 전달하고 임시 세션을 제거한다")
    void success_redirectsWithCode() throws Exception {
        when(socialMemberResolver.resolve(any())).thenReturn(1L);
        when(loginCodeRepository.issue(1L)).thenReturn("test-login-code");

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

        // 회원 확인이 완료된 뒤에만 로그인 코드를 발급해야 한다.
        var order = inOrder(socialMemberResolver, loginCodeRepository);
        order.verify(socialMemberResolver).resolve(any());
        order.verify(loginCodeRepository).issue(1L);
    }

    @Test
    @DisplayName("회원 검증에 실패하면 로그인 코드를 발급하지 않는다")
    void memberRejected_redirectsToFailure() throws Exception {
        when(socialMemberResolver.resolve(any()))
                .thenThrow(SocialLoginError.USER_NOT_ACTIVE.toException());

        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                request, response, googleAuthentication()
        );

        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/login?error=USER_NOT_ACTIVE"
        );
        verifyNoInteractions(loginCodeRepository);
    }

    @Test
    @DisplayName("코드 저장에 실패하면 성공 주소로 이동하지 않는다")
    void codeStorageFailed_redirectsToFailure() throws Exception {
        when(socialMemberResolver.resolve(any())).thenReturn(1L);
        when(loginCodeRepository.issue(1L))
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
        // 제공자 인증을 마친 상황을 재현하며 실제 구글 서버에는 접속하지 않는다.
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