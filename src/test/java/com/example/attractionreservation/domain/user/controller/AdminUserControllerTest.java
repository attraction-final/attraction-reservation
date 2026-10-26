package com.example.attractionreservation.domain.user.controller;

import com.example.attractionreservation.domain.user.dto.SearchUserResponse;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.dto.SearchUserCondition;
import com.example.attractionreservation.domain.user.dto.SearchUserListResponse;
import com.example.attractionreservation.domain.user.entity.Role;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminUserController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("관리자는 기본 조회 조건으로 회원 목록을 조회할 수 있다")
    void getUserAll_adminWithoutConditions_returnsDefaultPage() throws Exception {
        // given
        SearchUserListResponse response = new SearchUserListResponse(
                List.of(new SearchUserListResponse.Item(
                        1L,
                        "user@example.com",
                        "놀이공원회원",
                        Role.USER,
                        UserStatus.ACTIVE,
                        LocalDateTime.of(2026, 10, 15, 10, 0)
                )),
                0,
                10,
                1,
                1
        );

        when(userService.findUserAll(new SearchUserCondition(0, 10)))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/admin/users")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("회원 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].email")
                        .value("user@example.com"))
                .andExpect(jsonPath("$.data.content[0].nickname")
                        .value("놀이공원회원"))
                .andExpect(jsonPath("$.data.content[0].role").value("USER"))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.content[0].createdAt")
                        .value("2026-10-15T10:00:00"))
                .andExpect(jsonPath("$.data.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        verify(userService).findUserAll(new SearchUserCondition(0, 10));
    }

    @Test
    @DisplayName("관리자가 지정한 페이지 조건을 전달하고 빈 목록을 반환한다")
    void getUserAll_explicitConditions_returnsEmptyPage() throws Exception {
        // given
        when(userService.findUserAll(new SearchUserCondition(1, 5)))
                .thenReturn(new SearchUserListResponse(
                        List.of(), 1, 5, 0, 0
                ));

        // when & then
        mockMvc.perform(get("/api/admin/users")
                        .with(authenticatedAs("ADMIN"))
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        verify(userService).findUserAll(new SearchUserCondition(1, 5));
    }

    @Test
    @DisplayName("비로그인 요청은 HTTP 401을 반환한다")
    void getUserAll_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보를 전달하지 않는다.

        // when & then
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("일반 회원은 HTTP 403을 반환하고 조회 서비스를 호출하지 않는다")
    void getUserAll_regularUser_returnsForbidden() throws Exception {
        // given: 로그인했지만 관리자 권한이 없는 회원이다.

        // when & then
        mockMvc.perform(get("/api/admin/users")
                        .with(authenticatedAs("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    @ParameterizedTest
    @CsvSource({
            "-1, 10",
            "0, 0",
            "0, -1",
            "abc, 10",
            "0, abc"
    })
    @DisplayName("잘못된 페이지 조건은 HTTP 400을 반환한다")
    void getUserAll_invalidConditions_returnsBadRequest(
            String page,
            String size
    ) throws Exception {
        // given: 범위를 벗어나거나 숫자가 아닌 조회 조건이다.

        // when & then
        mockMvc.perform(get("/api/admin/users")
                        .with(authenticatedAs("ADMIN"))
                        .param("page", page)
                        .param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("관리자는 탈퇴 회원의 상세 정보도 조회할 수 있다")
    void getUserById_adminRequest_returnsMemberDetail() throws Exception {
        // given
        SearchUserResponse response = new SearchUserResponse(
                10L,
                "detail@example.com",
                "상세조회회원",
                Role.USER,
                UserStatus.WITHDRAWN,
                LocalDateTime.of(2026, 10, 15, 10, 0),
                LocalDateTime.of(2026, 10, 15, 12, 0)
        );

        when(userService.findUserById(10L)).thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/admin/users/{userId}", 10L)
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("회원 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.email")
                        .value("detail@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("상세조회회원"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.createdAt")
                        .value("2026-10-15T10:00:00"))
                .andExpect(jsonPath("$.data.updatedAt")
                        .value("2026-10-15T12:00:00"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(userService).findUserById(10L);
    }

    @Test
    @DisplayName("비로그인 사용자의 상세 조회는 HTTP 401을 반환한다")
    void getUserById_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보를 전달하지 않는다.

        // when & then
        mockMvc.perform(get("/api/admin/users/{userId}", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("일반 회원의 상세 조회는 HTTP 403을 반환한다")
    void getUserById_regularUser_returnsForbidden() throws Exception {
        // given: 관리자 권한 없이 요청한다.

        // when & then
        mockMvc.perform(get("/api/admin/users/{userId}", 10L)
                        .with(authenticatedAs("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("조회 대상 회원이 없으면 HTTP 404를 반환한다")
    void getUserById_missingMember_returnsNotFound() throws Exception {
        // given
        when(userService.findUserById(10L))
                .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/admin/users/{userId}", 10L)
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.USER_NOT_FOUND.getMessage()));

        verify(userService).findUserById(10L);
    }

    @Test
    @DisplayName("회원 ID가 숫자가 아니면 HTTP 400을 반환한다")
    void getUserById_invalidIdFormat_returnsBadRequest() throws Exception {
        // given: Long으로 변환할 수 없는 회원 ID를 전달한다.

        // when & then
        mockMvc.perform(get("/api/admin/users/{userId}", "abc")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(userService);
    }

    private RequestPostProcessor authenticatedAs(String role) {
        // 실제 JWT 대신 인증 정보를 주입해 API의 역할별 접근 제어를 검증한다.
        return authentication(new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }
}