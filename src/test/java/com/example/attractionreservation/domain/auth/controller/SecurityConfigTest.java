package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigTest.TestController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class,
        SecurityConfigTest.TestController.class
})
@EnableWebSecurity
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    void adminApi_withoutAuthentication_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/attractions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminApi_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/attractions")
                        .with(user("member").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminApi_withAdminRole_returnsOk() throws Exception {
        mockMvc.perform(get("/api/admin/attractions")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void attractionList_withoutAuthentication_returnsOk() throws Exception {
        mockMvc.perform(get("/api/attractions"))
                .andExpect(status().isOk());
    }

    @Test
    void attractionDetail_withoutAuthentication_returnsOk() throws Exception {
        mockMvc.perform(get("/api/attractions/1"))
                .andExpect(status().isOk());
    }

    @Test
    void attractionSlots_withoutAuthentication_returnsUnauthorized()
            throws Exception {
        mockMvc.perform(get("/api/attractions/1/slots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void attractionSlots_withUserRole_returnsOk() throws Exception {
        mockMvc.perform(get("/api/attractions/1/slots")
                        .with(user("member").roles("USER")))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus",
            "/readyz"
    })
    @DisplayName("헬스 체크와 메트릭 수집 경로는 인증 없이 접근할 수 있다")
    void actuator_withoutAuthentication_returnsOk(String path) throws Exception {
        // given
        // when
        // then
        mockMvc.perform(get(path))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("노출하지 않은 actuator 경로는 인증 없이 접근할 수 없다")
    void actuatorEnv_withoutAuthentication_returnsUnauthorized() throws Exception {
        // given
        // when
        // then
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로드밸런서가 쓰지 않는 서비스 포트의 liveness 경로는 인증 없이 접근할 수 없다")
    void livez_withoutAuthentication_returnsUnauthorized() throws Exception {
        // given
        // when
        // then
        mockMvc.perform(get("/livez"))
                .andExpect(status().isUnauthorized());
    }

    // 실제 서비스 로직 없이 보안 설정만 검증하는 테스트 전용 API
    // actuator 경로는 @WebMvcTest에 등록되지 않으므로 같은 경로를 매핑해 보안 설정만 확인한다.
    @RestController
    static class TestController {

        @GetMapping({
                "/api/admin/attractions",
                "/api/attractions",
                "/api/attractions/{attractionId}",
                "/api/attractions/{attractionId}/slots",
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/prometheus",
                "/actuator/env",
                "/readyz",
                "/livez"
        })
        public String success() {
            return "ok";
        }
    }
}