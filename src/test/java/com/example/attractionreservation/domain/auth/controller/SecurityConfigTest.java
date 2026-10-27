package com.example.attractionreservation.domain.auth.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import org.junit.jupiter.api.Test;
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

    // 실제 서비스 로직 없이 보안 설정만 검증하는 테스트 전용 API
    @RestController
    static class TestController {

        @GetMapping({
                "/api/admin/attractions",
                "/api/attractions",
                "/api/attractions/{attractionId}",
                "/api/attractions/{attractionId}/slots"
        })
        public String success() {
            return "ok";
        }
    }
}