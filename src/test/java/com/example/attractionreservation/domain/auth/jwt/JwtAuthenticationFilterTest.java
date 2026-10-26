
package com.example.attractionreservation.domain.auth.jwt;

import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtProvider jwtProvider;
    private UserRepository userRepository;
    private JwtAuthenticationEntryPoint authenticationEntryPoint;
    private FilterChain filterChain;
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        // JWT 필터의 의존성을 Mockito 객체로 대체한다.
        jwtProvider = mock(JwtProvider.class);
        userRepository = mock(UserRepository.class);
        authenticationEntryPoint = mock(JwtAuthenticationEntryPoint.class);
        filterChain = mock(FilterChain.class);

        jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtProvider,
                userRepository,
                authenticationEntryPoint
        );

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        // 테스트 간 인증 정보가 공유되지 않도록 정리한다.
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 요청에 잘못된 JWT가 있어도 JWT 검증을 건너뛴다.")
    void doFilter_loginWithInvalidToken_Success() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/auth/login");
        request.setServletPath("/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("Authorization", "Bearer invalid.jwt.token");

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtProvider, userRepository, authenticationEntryPoint);
    }

    @Test
    @DisplayName("회원가입 요청에 잘못된 JWT가 있어도 JWT 검증을 건너뛴다.")
    void doFilter_signupWithInvalidToken_Success() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/users/signup");
        request.setServletPath("/api/users/signup");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.addHeader("Authorization", "Bearer invalid.jwt.token");

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtProvider, userRepository, authenticationEntryPoint);
    }

    @Test
    @DisplayName("보호된 API에 잘못된 JWT를 사용하면 인증이 거부된다.")
    void doFilter_protectedApiWithInvalidToken_Fail() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/ticket-products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String invalidToken = "invalid.jwt.token";
        request.addHeader("Authorization", "Bearer " + invalidToken);

        when(jwtProvider.parseToken(invalidToken))
                .thenThrow(new MalformedJwtException("잘못된 JWT"));

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        // 인증 실패 처리기가 호출되고 요청은 다음 필터로 전달되지 않는다.
        verify(authenticationEntryPoint).commence(
                eq(request),
                eq(response),
                any()
        );
        verifyNoInteractions(filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    @DisplayName("보호된 API에 정상 JWT를 사용하면 사용자 인증에 성공한다.")
    void doFilter_protectedApiWithValidToken_Success() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/ticket-products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String validToken = "valid-access-token";
        request.addHeader("Authorization", "Bearer " + validToken);

        Claims claims = mock(Claims.class);

        when(jwtProvider.parseToken(validToken)).thenReturn(claims);
        when(claims.get("tokenType", String.class)).thenReturn("ACCESS");
        when(claims.getSubject()).thenReturn("1");

        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getRole()).thenReturn(Role.USER);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(authenticationEntryPoint);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull();

        assertThat(
                SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getPrincipal()
        ).isEqualTo(1L);

        assertThat(
                SecurityContextHolder.getContext()
                        .getAuthentication()
                        .getAuthorities()
        ).extracting("authority")
                .contains("ROLE_USER");
    }

    @Test
    @DisplayName("Refresh Token으로 일반 API를 호출하면 인증을 거부한다")
    void doFilter_refreshToken_Fail() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/orders");
        request.setServletPath("/api/orders");
        request.addHeader("Authorization", "Bearer refresh-token");

        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = mock(Claims.class);
        when(jwtProvider.parseToken("refresh-token")).thenReturn(claims);
        when(claims.get("tokenType", String.class)).thenReturn("REFRESH");

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any()
        );
        verifyNoInteractions(filterChain, userRepository);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    @DisplayName("토큰의 회원이 존재하지 않으면 인증을 거부한다")
    void doFilter_missingUser_Fail() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/orders");
        request.setServletPath("/api/orders");
        request.addHeader("Authorization", "Bearer access-token");

        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = mock(Claims.class);
        when(jwtProvider.parseToken("access-token")).thenReturn(claims);
        when(claims.get("tokenType", String.class)).thenReturn("ACCESS");
        when(claims.getSubject()).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(userRepository).findById(1L);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any()
        );
        verifyNoInteractions(filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }

    @Test
    @DisplayName("회원이 ACTIVE 상태가 아니면 인증을 거부한다")
    void doFilter_inactiveUser_Fail() throws Exception {
        // given
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/orders");
        request.setServletPath("/api/orders");
        request.addHeader("Authorization", "Bearer access-token");

        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = mock(Claims.class);
        when(jwtProvider.parseToken("access-token")).thenReturn(claims);
        when(claims.get("tokenType", String.class)).thenReturn("ACCESS");
        when(claims.getSubject()).thenReturn("1");

        User user = mock(User.class);
        when(user.getStatus()).thenReturn(UserStatus.INACTIVE);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        // when
        jwtAuthenticationFilter.doFilter(request, response, filterChain);

        // then
        verify(userRepository).findById(1L);
        verify(authenticationEntryPoint).commence(
                eq(request), eq(response), any()
        );
        verifyNoInteractions(filterChain);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNull();
    }
}
