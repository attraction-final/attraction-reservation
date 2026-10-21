package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginRequest;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginResponse;
import com.example.attractionreservation.domain.auth.dto.CreateAuthLoginServiceResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.auth.service.AuthService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest({AuthController.class, CsrfController.class})
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@AutoConfigureMockMvc(addFilters = true)
@EnableWebSecurity
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("로그인에 성공하면 Access Token 정보와 보안 속성이 적용된 Refresh Token 쿠키를 반환한다.")
    void login_validCredentials_returnsTokenResponseAndRefreshCookie()
            throws Exception {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        CreateAuthLoginResponse response =
                CreateAuthLoginResponse.of("access-token", 600L);

        when(authService.login(request))
                .thenReturn(CreateAuthLoginServiceResponse.of(
                        response, "refresh-token"
                ));

        // 설정값을 사용하는지 확인하기 위해 기본 7일 대신 3일을 반환한다.
        when(jwtProvider.getRefreshTokenExpirationSeconds())
                .thenReturn(259_200L);

        // when & then
        mockMvc.perform(post("/api/auth/login")
                        .servletPath("/api/auth/login")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "password": "Password!123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("로그인에 성공했습니다."))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(600))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refreshToken=refresh-token"),
                                containsString("Max-Age=259200"),
                                containsString("HttpOnly"),
                                containsString("Secure"),
                                containsString("SameSite=Lax"),
                                containsString("Path=/api/auth")
                        )
                ));

        verify(authService).login(request);
        verify(jwtProvider).getRefreshTokenExpirationSeconds();
        verifyNoMoreInteractions(jwtProvider);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("잘못된 Authorization 헤더가 있어도 로그인 요청은 Access Token 검증을 건너뛴다.")
    void login_invalidAuthorizationHeader_returnsOk() throws Exception {
        // given
        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        when(authService.login(request))
                .thenReturn(CreateAuthLoginServiceResponse.of(
                        CreateAuthLoginResponse.of("access-token", 600L),
                        "refresh-token"
                ));
        when(jwtProvider.getRefreshTokenExpirationSeconds())
                .thenReturn(604_800L);

        // when & then
        mockMvc.perform(post("/api/auth/login")
                        .servletPath("/api/auth/login")
                        .with(csrf().asHeader())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "password": "Password!123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));

        verify(authService).login(request);
        verify(jwtProvider).getRefreshTokenExpirationSeconds();
        verifyNoMoreInteractions(jwtProvider);
        verifyNoInteractions(userRepository);
    }
    @Test
    @DisplayName("인증 없이 관리자 API를 호출하면 HTTP 401과 공통 오류 응답을 반환한다.")
    void createTicketProduct_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보 없이 관리자 전용 API에 요청한다.

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .servletPath("/api/ticket-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.UNAUTHORIZED.getMessage()));

        verifyNoInteractions(authService, jwtProvider, userRepository);
    }

    @Test
    @DisplayName("일반 회원이 관리자 API를 호출하면 HTTP 403과 공통 오류 응답을 반환한다.")
    void createTicketProduct_regularUser_returnsForbidden() throws Exception {
        // given: 테스트용 일반 회원 인증을 요청에 적용한다.

        // when & then
        mockMvc.perform(post("/api/ticket-products")
                        .servletPath("/api/ticket-products")
                        .with(user("test-user").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.ACCESS_DENIED.getMessage()));

        verifyNoInteractions(authService, jwtProvider, userRepository);
    }

    @Test
    @DisplayName("조회한 CSRF 토큰과 쿠키를 함께 보내면 로그인에 성공한다.")
    void login_issuedCsrfTokenAndCookie_returnsOk() throws Exception {
        // given
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")
                        .servletPath("/api/auth/csrf")
                        .secure(true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")
                ))
                .andReturn();

        var data = objectMapper.readTree(
                csrfResult.getResponse().getContentAsString()
        ).get("data");

        String headerName = data.get("headerName").asText();
        String token = data.get("token").asText();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");

        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isTrue();
        assertThat(csrfCookie.getSecure()).isTrue();
        assertThat(csrfCookie.getPath()).isEqualTo("/");
        assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");

        CreateAuthLoginRequest request =
                new CreateAuthLoginRequest("test@example.com", "Password!123");

        when(authService.login(request))
                .thenReturn(CreateAuthLoginServiceResponse.of(
                        CreateAuthLoginResponse.of("access-token", 600L),
                        "refresh-token"
                ));
        when(jwtProvider.getRefreshTokenExpirationSeconds())
                .thenReturn(604_800L);

        // when & then
        mockMvc.perform(post("/api/auth/login")
                        .servletPath("/api/auth/login")
                        .secure(true)
                        .cookie(csrfCookie)
                        .header(headerName, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));

        verify(authService).login(request);
    }

    @Test
    @DisplayName("CSRF 토큰 없이 로그인하면 HTTP 403을 반환하고 서비스를 호출하지 않는다.")
    void login_missingCsrfToken_returnsForbidden() throws Exception {
        // given: 로그인 입력은 유효하지만 CSRF 토큰이 없다.

        // when & then
        mockMvc.perform(post("/api/auth/login")
                        .servletPath("/api/auth/login")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON
                ))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.ACCESS_DENIED.getMessage()));

        verifyNoInteractions(authService, jwtProvider, userRepository);
    }

    @Test
    @DisplayName("발급받은 쿠키가 있어도 CSRF 헤더가 잘못되면 로그인을 거부한다.")
    void login_invalidCsrfToken_returnsForbidden() throws Exception {
        // given
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")
                        .servletPath("/api/auth/csrf")
                        .secure(true))
                .andExpect(status().isOk())
                .andReturn();

        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();

        // when & then
        mockMvc.perform(post("/api/auth/login")
                        .servletPath("/api/auth/login")
                        .secure(true)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginRequestBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.ACCESS_DENIED.getMessage()));

        verifyNoInteractions(authService, jwtProvider, userRepository);
    }

    private String loginRequestBody() {
        return """
            {
              "email": "test@example.com",
              "password": "Password!123"
            }
            """;
    }
}
