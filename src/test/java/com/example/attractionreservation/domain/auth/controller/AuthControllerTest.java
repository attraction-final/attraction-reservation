
package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
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
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.user.repository.UserRepository;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

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
    @DisplayName("정상 로그아웃 시 HTTP 200을 반환하고 Refresh Token 쿠키를 삭제한다.")
    void logout_validTokens_returnsOkAndClearsCookie() throws Exception {
        // given
        Long userId = 1L;
        String refreshToken = "valid-refresh-token";

        // when & then
        mockMvc.perform(post("/api/auth/logout")
                        .with(authentication(
                                new UsernamePasswordAuthenticationToken(
                                        userId,
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                                )
                        ))
                        .cookie(new jakarta.servlet.http.Cookie("refreshToken", refreshToken)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("로그아웃에 성공했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("refreshToken="),
                                org.hamcrest.Matchers.containsString("Max-Age=0"),
                                org.hamcrest.Matchers.containsString("HttpOnly"),
                                org.hamcrest.Matchers.containsString("Secure"),
                                org.hamcrest.Matchers.containsString("SameSite=Lax"),
                                org.hamcrest.Matchers.containsString("Path=/api/auth")
                        )));

        verify(authService).logout(userId, refreshToken);
    }

    @Test
    @DisplayName("Access Token이 없으면 로그아웃할 수 없다.")
    void logout_withoutAccessToken_returnsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(
                                "refreshToken", "valid-refresh-token"
                        )))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).logout(any(), any());
    }

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }).when(jwtAuthenticationEntryPoint).commence(any(), any(), any());
    }

    @Test
    @DisplayName("Refresh Token이 일치하지 않으면 HTTP 401을 반환한다.")
    void logout_invalidRefreshToken_returnsUnauthorized() throws Exception {
        // given
        Long userId = 1L;
        String refreshToken = "invalid-refresh-token";

        doThrow(new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN))
                .when(authService).logout(userId, refreshToken);

        // when & then
        mockMvc.perform(post("/api/auth/logout")
                        .with(authentication(
                                new UsernamePasswordAuthenticationToken(
                                        userId,
                                        null,
                                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                                )
                        ))
                        .cookie(new jakarta.servlet.http.Cookie(
                                "refreshToken", refreshToken
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verify(authService).logout(userId, refreshToken);
    }


}
