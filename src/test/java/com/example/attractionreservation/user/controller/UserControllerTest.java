package com.example.attractionreservation.user.controller;

import com.example.attractionreservation.domain.user.dto.UpdatePasswordRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.controller.UserController;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.dto.GetUserResponse;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import com.example.attractionreservation.domain.user.dto.UpdateUserRequest;
import com.example.attractionreservation.domain.user.dto.UpdateUserResponse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
@AutoConfigureMockMvc(addFilters = true)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void signup_success() throws Exception {
        CreateUserResponse response = new CreateUserResponse(
                1L, "test@gmail.com", "홍길동", "USER", "ACTIVE"
        );

        when(userService.signup(any())).thenReturn(response);

        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "test@gmail.com",
                                    "password": "Password123!",
                                    "nickname": "홍길동"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.data.nickname").value("홍길동"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void signup_invalidEmail_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "invalid-email",
                                    "password": "Password123!",
                                    "nickname": "홍길동"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signup_duplicateEmail_returnsConflict() throws Exception {
        when(userService.signup(any()))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "test@gmail.com",
                                    "password": "Password123!",
                                    "nickname": "홍길동"
                                }
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void signup_passwordExceeds72Bytes_returnsBadRequest() throws Exception {
        String password = "a1" + "😀".repeat(18);

        String requestBody = """
                {
                    "email": "test@gmail.com",
                    "password": "%s",
                    "nickname": "홍길동"
                }
                """.formatted(password);

        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(userService, never()).signup(any());
    }

    @Test
    void signup_passwordExactly72Bytes_returnsCreated() throws Exception {
        // 영문·숫자 2바이트 + 이모지 68바이트 + é 2바이트
        String password = "a1" + "😀".repeat(17) + "é";

        CreateUserResponse response = new CreateUserResponse(
                1L, "test@gmail.com", "홍길동", "USER", "ACTIVE"
        );

        when(userService.signup(any())).thenReturn(response);

        String requestBody = """
                {
                    "email": "test@gmail.com",
                    "password": "%s",
                    "nickname": "홍길동"
                }
                """.formatted(password);

        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1));

        verify(userService, times(1)).signup(any());
    }

    @Test
    void getMe_authenticated_returnsOwnInfo() throws Exception {
        Long userId = 1L;
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        when(userService.getMe(userId))
                .thenReturn(new GetUserResponse(
                        userId, "test@gmail.com", "홍길동", "USER", "ACTIVE"
                ));

        // 다른 ID를 보내도 인증된 본인의 ID로 조회한다.
        mockMvc.perform(get("/api/users/me")
                        .with(authentication(authenticatedUser))
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("회원 정보 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.data.nickname").value("홍길동"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(userService).getMe(userId);
        verifyNoMoreInteractions(userService);
    }

    @Test
    void getMe_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.UNAUTHORIZED.getMessage()));

        verifyNoInteractions(userService);
    }

    @Test
    void getMe_invalidAccessToken_returnsUnauthorized() throws Exception {
        when(jwtProvider.parseToken("invalid-token"))
                .thenThrow(new JwtException("유효하지 않은 토큰"));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.UNAUTHORIZED.getMessage()));

        verify(jwtProvider).parseToken("invalid-token");
        verifyNoInteractions(userService, userRepository);
    }

    @Test
    void updateMe_authenticated_updatesOwnNickname() throws Exception {
        Long userId = 1L;
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        UpdateUserRequest request = new UpdateUserRequest("새로운닉네임");

        when(userService.updateMe(userId, request))
                .thenReturn(new UpdateUserResponse(
                        userId, "test@gmail.com", "새로운닉네임"
                ));

        mockMvc.perform(patch("/api/users/me")
                        .with(authentication(authenticatedUser))
                        .param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "nickname": "새로운닉네임"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("회원 정보 수정에 성공했습니다."))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.email").value("test@gmail.com"))
                .andExpect(jsonPath("$.data.nickname").value("새로운닉네임"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(userService).updateMe(userId, request);
        verifyNoMoreInteractions(userService);
    }

    @Test
    void updateMe_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "nickname": "새로운닉네임"
                            }
                            """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @Test
    void updateMe_blankNickname_returnsBadRequest() throws Exception {
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/users/me")
                        .with(authentication(authenticatedUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "nickname": "   "
                            }
                            """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void updateMe_missingNickname_returnsBadRequest() throws Exception {
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/users/me")
                        .with(authentication(authenticatedUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void updateMe_nicknameExceeds50Characters_returnsBadRequest()
            throws Exception {
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        String requestBody = """
            {
                "nickname": "%s"
            }
            """.formatted("가".repeat(51));

        mockMvc.perform(patch("/api/users/me")
                        .with(authentication(authenticatedUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

    @Test
    void updatePassword_authenticated_returnsOk() throws Exception {
        Long userId = 1L;
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        // 다른 회원 ID를 보내더라도 인증된 본인의 비밀번호만 변경한다.
        mockMvc.perform(patch("/api/users/me/password")
                        .with(authentication(authenticatedUser))
                        .param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "currentPassword": "Password!123",
                                "newPassword": "NewPassword!456"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("비밀번호 변경에 성공했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        verify(userService).updatePassword(
                userId,
                new UpdatePasswordRequest("Password!123", "NewPassword!456")
        );
        verifyNoMoreInteractions(userService);
    }

    @Test
    void updatePassword_wrongCurrentPassword_returnsBadRequest() throws Exception {
        Long userId = 1L;
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                userId,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        UpdatePasswordRequest request = new UpdatePasswordRequest(
                "WrongPassword!123", "NewPassword!456"
        );

        doThrow(new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH))
                .when(userService).updatePassword(userId, request);

        mockMvc.perform(patch("/api/users/me/password")
                        .with(authentication(authenticatedUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "currentPassword": "WrongPassword!123",
                                "newPassword": "NewPassword!456"
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.CURRENT_PASSWORD_MISMATCH.getMessage()));

        verify(userService).updatePassword(userId, request);
    }

    @Test
    void updatePassword_withoutAuthentication_returnsUnauthorized()
            throws Exception {
        mockMvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "currentPassword": "Password!123",
                                "newPassword": "NewPassword!456"
                            }
                            """))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"currentPassword\":\"Password!123\"}",
            "{\"newPassword\":\"NewPassword!456\"}",
            "{\"currentPassword\":\"   \",\"newPassword\":\"NewPassword!456\"}",
            "{\"currentPassword\":\"Password!123\",\"newPassword\":\"   \"}",
            "{\"currentPassword\":\"Password!123\",\"newPassword\":\"Ab1!\"}",
            "{\"currentPassword\":\"Password!123\",\"newPassword\":\"abcdefgh123\"}",
            "{\"currentPassword\":\"Password!123\",\"newPassword\":\"Abcdefghijklmnopqr123!\"}"
    })
    void updatePassword_invalidInput_returnsBadRequest(String requestBody)
            throws Exception {
        var authenticatedUser = new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/users/me/password")
                        .with(authentication(authenticatedUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userService);
    }

}