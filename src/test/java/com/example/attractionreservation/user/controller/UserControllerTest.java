package com.example.attractionreservation.user.controller;

import com.example.attractionreservation.domain.user.controller.UserController;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.user.dto.CreateUserResponse;
import com.example.attractionreservation.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

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
        // given
        CreateUserResponse response = new CreateUserResponse(
                1L,
                "test@gmail.com",
                "홍길동",
                "USER",
                "ACTIVE"
        );

        when(userService.signup(any()))
                .thenReturn(response);

        // when & then
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
        // when & then
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
        // given
        when(userService.signup(any()))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_EMAIL));

        // when & then
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
        // given
        String password = "a1" + "😀".repeat(18);

        String requestBody = """
            {
                "email": "test@gmail.com",
                "password": "%s",
                "nickname": "홍길동"
            }
            """.formatted(password);

        // when & then
        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(userService, never()).signup(any());
    }

    @Test
    void signup_passwordExactly72Bytes_returnsCreated() throws Exception {
        // given: 영문 2바이트 + 이모지 68바이트 + é 2바이트 = 72바이트
        String password = "a1" + "😀".repeat(17) + "é";

        CreateUserResponse response = new CreateUserResponse(
                1L,
                "test@gmail.com",
                "홍길동",
                "USER",
                "ACTIVE"
        );

        when(userService.signup(any()))
                .thenReturn(response);

        String requestBody = """
            {
                "email": "test@gmail.com",
                "password": "%s",
                "nickname": "홍길동"
            }
            """.formatted(password);

        // when & then
        mockMvc.perform(post("/api/users/signup")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1));

        verify(userService, times(1)).signup(any());
    }
}