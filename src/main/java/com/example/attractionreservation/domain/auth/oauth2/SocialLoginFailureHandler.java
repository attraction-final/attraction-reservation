package com.example.attractionreservation.domain.auth.oauth2;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationServiceException;

import java.io.IOException;

@Slf4j
@Component
@Profile("oauth")
public class SocialLoginFailureHandler
        implements AuthenticationFailureHandler {

    private final String failureRedirectUri;

    public SocialLoginFailureHandler(
            @Value("${app.oauth2.failure-redirect-uri}")
            String failureRedirectUri
    ) {
        this.failureRedirectUri = failureRedirectUri;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {

        // 실패한 인증 요청에 사용한 임시 세션과 인증 정보를 정리한다.
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        SocialLoginError error = resolveError(exception);

        if (exception instanceof AuthenticationServiceException) {
            Throwable cause = exception.getCause();

            log.error(
                    "event=SOCIAL_LOGIN_POSTPROCESS_FAILED result=FAILURE "
                            + "errorCode={} exceptionType={}",
                    error.name(),
                    cause == null
                            ? exception.getClass().getSimpleName()
                            : cause.getClass().getSimpleName()
            );
        } else {
            log.warn(
                    "event=SOCIAL_AUTHENTICATION_REJECTED result=REJECTED "
                            + "errorCode={}",
                    error.name()
            );
        }

        // 제공자의 원본 오류 메시지 대신 서비스에서 정한 코드만 전달한다.
        String redirectUri = UriComponentsBuilder
                .fromUriString(failureRedirectUri)
                .replaceQueryParam("error", error.name())
                .build()
                .encode()
                .toUriString();

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(redirectUri);
    }

    private SocialLoginError resolveError(
            AuthenticationException exception
    ) {
        if (exception instanceof OAuth2AuthenticationException oauthException) {
            String code = oauthException.getError().getErrorCode();

            for (SocialLoginError error : SocialLoginError.values()) {
                if (error.name().equals(code)) {
                    return error;
                }
            }
        }

        // state 불일치 등 Spring Security의 오류는 공통 실패 코드로 변환한다.
        return SocialLoginError.AUTHENTICATION_FAILED;
    }
}