package com.example.attractionreservation.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@Profile("oauth")
public class OAuth2SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain oauth2SecurityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrationRepository
    ) throws Exception {

        // 제공자 인증 화면으로 이동시키고, 인증 요청 정보를 임시 세션에 저장한다.
        var authorizationRequestRepository =
                new HttpSessionOAuth2AuthorizationRequestRepository();

        var redirectFilter = new OAuth2AuthorizationRequestRedirectFilter(
                clientRegistrationRepository
        );
        redirectFilter.setAuthorizationRequestRepository(
                authorizationRequestRepository
        );

        http
                // 소셜 로그인 시작 경로에만 적용한다.
                .securityMatcher("/oauth2/authorization/**")

                // 임시 세션은 인증 요청과 콜백을 연결하는 용도로만 사용한다.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )

                // 소셜 로그인 과정의 인증 정보를 로그인 세션으로 저장하지 않는다.
                .securityContext(context -> context
                        .securityContextRepository(
                                new NullSecurityContextRepository()
                        )
                )

                // 인증 시작 전 요청을 저장하거나 기본 로그인 화면으로 보내지 않는다.
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())

                // 유효한 인증 시작 요청은 앞의 필터에서 리다이렉트한다.
                // 필터에서 처리하지 않은 요청은 접근을 허용하지 않는다.
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().denyAll()
                )

                .addFilterBefore(
                        redirectFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}