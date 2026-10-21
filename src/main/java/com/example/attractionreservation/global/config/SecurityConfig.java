
package com.example.attractionreservation.global.config;

import com.example.attractionreservation.domain.auth.jwt.JwtAuthenticationFilter;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final UserRepository userRepository;
    private static final String TICKET_PRODUCTS_PATH = "/api/ticket-products";
    private static final String TICKET_PRODUCTS_SUB_PATH = "/api/ticket-products/**";
    private static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        var paths = PathPatternRequestMatcher.withDefaults();

// 안전하지 않은 HTTP 메서드이면서 인증 관련 경로인 요청만 보호한다.
        var csrfProtectedRequests = new AndRequestMatcher(
                CsrfFilter.DEFAULT_CSRF_MATCHER,
                new OrRequestMatcher(
                        paths.matcher("/api/auth/**"),
                        paths.matcher("/api/users/signup")
                )
        );

        var csrfTokenRepository = new CookieCsrfTokenRepository();
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
        );


        http
                // JWT 기반 인증을 사용하므로 서버에 로그인 세션을 생성하지 않는다.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 로그인·회원가입 및 쿠키를 사용하는 인증 요청에 CSRF 토큰 검증을 적용한다.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .requireCsrfProtectionMatcher(csrfProtectedRequests)
                )

// API별 인증 및 접근 권한을 설정한다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        // 회원가입과 로그인은 인증 없이 접근할 수 있다.
                        .requestMatchers(HttpMethod.POST, "/api/users/signup").permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/refresh"
                        ).permitAll()

                        // 티켓 상품 등록·수정·삭제는 관리자만 가능하다.
                        .requestMatchers(
                                HttpMethod.POST,
                                TICKET_PRODUCTS_PATH,
                                TICKET_PRODUCTS_SUB_PATH
                        ).hasRole(ADMIN_ROLE)
                        .requestMatchers(
                                HttpMethod.PUT,
                                TICKET_PRODUCTS_PATH,
                                TICKET_PRODUCTS_SUB_PATH
                        ).hasRole(ADMIN_ROLE)
                        .requestMatchers(
                                HttpMethod.PATCH,
                                TICKET_PRODUCTS_PATH,
                                TICKET_PRODUCTS_SUB_PATH
                        ).hasRole(ADMIN_ROLE)
                        .requestMatchers(
                                HttpMethod.DELETE,
                                TICKET_PRODUCTS_PATH,
                                TICKET_PRODUCTS_SUB_PATH
                        ).hasRole(ADMIN_ROLE)

                        // 그 외 API는 로그인한 사용자만 접근할 수 있다.
                        .anyRequest().authenticated())

                // 인증 실패(401)와 권한 부족(403)을 각각 처리한다.
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler))

                // API 접근 권한을 판단하기 전에 JWT를 검증한다.
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtProvider,
                                userRepository,
                                jwtAuthenticationEntryPoint
                        ),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
