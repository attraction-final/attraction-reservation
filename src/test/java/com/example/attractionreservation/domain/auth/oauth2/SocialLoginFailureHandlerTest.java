package com.example.attractionreservation.domain.auth.oauth2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.assertj.core.api.Assertions.assertThat;

class SocialLoginFailureHandlerTest {

    private final SocialLoginFailureHandler handler =
            new SocialLoginFailureHandler(
                    "http://localhost:3000/login"
            );

    @Test
    @DisplayName("업무 오류 코드를 전달하고 임시 세션을 제거한다")
    void knownError_redirectsAndInvalidatesSession() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var session = new MockHttpSession();
        session.setAttribute("temporary", "authorization-request");
        request.setSession(session);

        handler.onAuthenticationFailure(
                request,
                response,
                SocialLoginError.EMAIL_REQUIRED.toException()
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/login?error=EMAIL_REQUIRED"
        );
        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy"))
                .isEqualTo("no-referrer");
    }

    @Test
    @DisplayName("제공자의 원본 오류는 공통 실패 코드로 변환한다")
    void unknownError_doesNotExposeProviderDetails() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();

        var exception = new OAuth2AuthenticationException(
                new OAuth2Error(
                        "invalid_grant",
                        "provider-internal-error-details",
                        null
                )
        );

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://localhost:3000/login?error=AUTHENTICATION_FAILED"
        );

        // 원래 세션이 없었다면 실패 응답을 위해 새로 만들지 않는다.
        assertThat(request.getSession(false)).isNull();
    }
}