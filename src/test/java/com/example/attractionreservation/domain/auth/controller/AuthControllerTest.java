package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.dto.CreateAuthRefreshResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
@EnableWebSecurity
@AutoConfigureMockMvc(addFilters = true)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @MockitoBean
    private JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Test
    @DisplayName("Access Token 없이 Refresh Token 쿠키로 재발급하면 HTTP 200과 토큰 정보를 반환한다.")
    void refresh_withoutAccessToken_returnsOkWithoutSettingCookie() throws Exception {
        // given
        String refreshToken = "valid-refresh-token";

        when(authService.refresh(refreshToken))
                .thenReturn(CreateAuthRefreshResponse.of(
                        "new-access-token", 600L
                ));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/refresh")
                .servletPath("/api/auth/refresh")
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        expectRefreshSuccess(result);

        verify(authService).refresh(refreshToken);
        verifyNoInteractions(
                jwtProvider,
                userRepository,
                jwtAuthenticationEntryPoint
        );
    }

    // 재발급 경로는 Authorization 헤더를 검증하지 않고 Refresh Token 검증을 서비스에 위임한다.
    @Test
    @DisplayName("유효하지 않은 Authorization 헤더가 있어도 재발급 경로는 Access Token 검증을 건너뛴다.")
    void refresh_invalidAuthorizationHeader_returnsOkWithoutSettingCookie() throws Exception {
        // given
        String refreshToken = "valid-refresh-token";

        when(authService.refresh(refreshToken))
                .thenReturn(CreateAuthRefreshResponse.of(
                        "new-access-token", 600L
                ));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/refresh")
                .servletPath("/api/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-access-token")
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        expectRefreshSuccess(result);

        verify(authService).refresh(refreshToken);
        verifyNoInteractions(
                jwtProvider,
                userRepository,
                jwtAuthenticationEntryPoint
        );
    }

    @Test
    @DisplayName("Refresh Token 쿠키가 없으면 서비스에 null을 전달하고 HTTP 401을 반환한다.")
    void refresh_missingRefreshTokenCookie_returnsUnauthorized() throws Exception {
        // given
        when(authService.refresh(null))
                .thenThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/refresh")
                .servletPath("/api/auth/refresh"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REFRESH_TOKEN.getMessage()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService).refresh(null);
    }

    @Test
    @DisplayName("서비스가 Refresh Token 검증을 거부하면 HTTP 401을 반환한다.")
    void refresh_invalidRefreshToken_returnsUnauthorized() throws Exception {
        // given
        String refreshToken = "invalid-refresh-token";

        when(authService.refresh(refreshToken))
                .thenThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/refresh")
                .servletPath("/api/auth/refresh")
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REFRESH_TOKEN.getMessage()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService).refresh(refreshToken);
    }

    @Test
    @DisplayName("서비스가 회원 상태로 재발급을 거부하면 HTTP 403을 반환한다.")
    void refresh_nonActiveUser_returnsForbidden() throws Exception {
        // given
        String refreshToken = "valid-refresh-token";

        when(authService.refresh(refreshToken))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_ACTIVE));

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/refresh")
                .servletPath("/api/auth/refresh")
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.USER_NOT_ACTIVE.getMessage()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService).refresh(refreshToken);
    }

    @Test
    @DisplayName("인증된 회원의 로그아웃에 성공하면 HTTP 200을 반환하고 Refresh Token 쿠키를 삭제한다.")
    void logout_authenticatedUser_returnsOkAndClearsCookie() throws Exception {
        // given
        Long userId = 1L;
        String refreshToken = "valid-refresh-token";

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/logout")
                .servletPath("/api/auth/logout")
                .with(authenticatedUser(userId))
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("로그아웃에 성공했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refreshToken="),
                                containsString("Max-Age=0"),
                                containsString("HttpOnly"),
                                containsString("Secure"),
                                containsString("SameSite=Lax"),
                                containsString("Path=/api/auth")
                        )
                ));

        verify(authService).logout(userId, refreshToken);
    }

    // Mock으로 대체한 인증 실패 처리기가 실제로 HTTP 401을 설정하도록 구성한다.
    @Test
    @DisplayName("인증되지 않은 로그아웃 요청은 HTTP 401을 반환하고 서비스를 호출하지 않는다.")
    void logout_withoutAccessToken_returnsUnauthorized() throws Exception {
        // given
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }).when(jwtAuthenticationEntryPoint).commence(any(), any(), any());

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/logout")
                .servletPath("/api/auth/logout")
                .cookie(new Cookie("refreshToken", "valid-refresh-token")));

        // then
        result.andExpect(status().isUnauthorized());

        verify(jwtAuthenticationEntryPoint).commence(any(), any(), any());
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("서비스가 Refresh Token 검증을 거부하면 로그아웃은 HTTP 401을 반환한다.")
    void logout_invalidRefreshToken_returnsUnauthorized() throws Exception {
        // given
        Long userId = 1L;
        String refreshToken = "invalid-refresh-token";

        doThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN))
                .when(authService).logout(userId, refreshToken);

        // when
        ResultActions result = mockMvc.perform(post("/api/auth/logout")
                .servletPath("/api/auth/logout")
                .with(authenticatedUser(userId))
                .cookie(new Cookie("refreshToken", refreshToken)));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REFRESH_TOKEN.getMessage()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        verify(authService).logout(userId, refreshToken);
    }

    private void expectRefreshSuccess(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("토큰 재발급에 성공했습니다."))
                .andExpect(jsonPath("$.data.accessToken")
                        .value("new-access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(600))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private RequestPostProcessor authenticatedUser(Long userId) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                )
        );
    }
}