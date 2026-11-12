package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.auth.service.SocialLoginService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.authentication.AuthenticationServiceException;

import java.io.IOException;

@Slf4j
@Component
@Profile("oauth")
public class SocialLoginSuccessHandler
        implements AuthenticationSuccessHandler {

    private final SocialUserInfoMapper userInfoMapper;
    private final SocialLoginService socialLoginService;
    private final SocialLoginFailureHandler failureHandler;
    private final String successRedirectUri;

    public SocialLoginSuccessHandler(
            SocialUserInfoMapper userInfoMapper,
            SocialLoginService socialLoginService,
            SocialLoginFailureHandler failureHandler,
            @Value("${app.oauth2.success-redirect-uri}")
            String successRedirectUri
    ) {
        this.userInfoMapper = userInfoMapper;
        this.socialLoginService = socialLoginService;
        this.failureHandler = failureHandler;
        this.successRedirectUri = successRedirectUri;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {

        String code;

        try {
            if (!(authentication instanceof OAuth2AuthenticationToken token)) {
                throw SocialLoginError.AUTHENTICATION_FAILED.toException();
            }

            // 제공자 인증은 Spring Security가 검증하고,
            // 서비스 회원 식별과 가입 정책은 우리 서비스에서 검증한다.
            var info = userInfoMapper.map(
                    token.getAuthorizedClientRegistrationId(),
                    token.getPrincipal().getAttributes()
            );

            // 회원 확인과 코드 발급 순서는 서비스에서 관리한다.
            code = socialLoginService.login(info);

        } catch (OAuth2AuthenticationException exception) {
            failureHandler.onAuthenticationFailure(
                    request, response, exception
            );
            return;

        } catch (RuntimeException exception) {
            // DB·Redis 오류 등으로 처리가 완료되지 않으면 성공으로 응답하지 않는다.
            // 원인은 실패 처리기로 전달하고 로그는 그곳에서 한 번 기록한다.
            failureHandler.onAuthenticationFailure(
                    request,
                    response,
                    new AuthenticationServiceException(
                            "소셜 로그인 후처리 실패",
                            exception
                    )
            );
            return;
        }

        // 인증 과정의 임시 세션을 정리하고 서비스 로그인 세션은 남기지 않는다.
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();

        String redirectUri = UriComponentsBuilder
                .fromUriString(successRedirectUri)
                .replaceQueryParam("code", code)
                .build()
                .encode()
                .toUriString();

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.sendRedirect(redirectUri);
    }
}