package com.example.attractionreservation.global.config;

import com.example.attractionreservation.domain.auth.oauth2.SocialLoginFailureHandler;
import com.example.attractionreservation.domain.auth.oauth2.SocialLoginSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@Profile("oauth")
public class OAuth2SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain oauth2SecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository,
            SocialLoginSuccessHandler successHandler,
            SocialLoginFailureHandler failureHandler
    ) throws Exception {

        var authorizationRequestRepository =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        // 제공자 토큰도 임시 세션에만 보관하고, 성공 처리 후 세션과 함께 제거한다.
        var authorizedClientRepository =
                new HttpSessionOAuth2AuthorizedClientRepository();

        http
                // 시작 요청과 제공자 인증 결과 콜백에만 적용한다.
                .securityMatcher(
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**"
                )

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )

                // 서비스 인증은 JWT로 처리하므로 로그인 세션은 저장하지 않는다.
                .securityContext(context -> context
                        .securityContextRepository(
                                new NullSecurityContextRepository()
                        )
                )

                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())

                // 정상 시작·콜백 요청은 OAuth2 필터가 처리한다.
                // 그 외 요청은 허용하지 않는다.
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().denyAll()
                )

                .oauth2Login(oauth2 -> oauth2
                        .clientRegistrationRepository(
                                clientRegistrationRepository
                        )
                        .authorizedClientRepository(
                                authorizedClientRepository
                        )
                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestRepository(
                                        authorizationRequestRepository
                                )
                        )
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                );

        return http.build();
    }
}