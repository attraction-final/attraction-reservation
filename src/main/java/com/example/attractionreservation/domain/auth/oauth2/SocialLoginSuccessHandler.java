package com.example.attractionreservation.domain.auth.oauth2;

import com.example.attractionreservation.domain.auth.repository.SocialLoginCodeRepository;
import com.example.attractionreservation.domain.auth.service.SocialMemberResolver;
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

import java.io.IOException;

@Slf4j
@Component
@Profile("oauth")
public class SocialLoginSuccessHandler
        implements AuthenticationSuccessHandler {

    private final SocialUserInfoMapper userInfoMapper;
    private final SocialMemberResolver socialMemberResolver;
    private final SocialLoginCodeRepository loginCodeRepository;
    private final SocialLoginFailureHandler failureHandler;
    private final String successRedirectUri;

    public SocialLoginSuccessHandler(
            SocialUserInfoMapper userInfoMapper,
            SocialMemberResolver socialMemberResolver,
            SocialLoginCodeRepository loginCodeRepository,
            SocialLoginFailureHandler failureHandler,
            @Value("${app.oauth2.success-redirect-uri}")
            String successRedirectUri
    ) {
        this.userInfoMapper = userInfoMapper;
        this.socialMemberResolver = socialMemberResolver;
        this.loginCodeRepository = loginCodeRepository;
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

            Long userId = socialMemberResolver.resolve(info);

            // 회원 저장 트랜잭션이 완료된 뒤 일회용 코드를 발급한다.
            code = loginCodeRepository.issue(userId);

        } catch (OAuth2AuthenticationException exception) {
            failureHandler.onAuthenticationFailure(
                    request, response, exception
            );
            return;

        } catch (RuntimeException exception) {
            // DB·Redis 오류 등으로 처리가 완료되지 않으면 성공으로 응답하지 않는다.
            // 개인정보나 원본 로그인 코드는 로그에 남기지 않는다.
            log.error(
                    "소셜 로그인 후처리 실패: {}",
                    exception.getClass().getSimpleName()
            );

            failureHandler.onAuthenticationFailure(
                    request,
                    response,
                    SocialLoginError.AUTHENTICATION_FAILED.toException()
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