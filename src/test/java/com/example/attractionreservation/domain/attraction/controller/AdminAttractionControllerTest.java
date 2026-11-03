package com.example.attractionreservation.domain.attraction.controller;

import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusResponse;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 컨트롤러만 띄워서 "요청 → 서비스 호출 → 응답" 연결이 맞는지 확인한다.
// 서비스는 가짜(Mock)로 바꿔서, 비즈니스 로직은 서비스 테스트가 따로 검증한다.
@WebMvcTest(AdminAttractionController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class AdminAttractionControllerTest {

    private static final Long ADMIN_ID = 1L; // authenticatedAs()가 넣는 로그인 ID

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttractionService attractionService;

    // 보안 필터가 필요로 하는 빈 (AdminUserControllerTest와 동일)
    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("관리자는 어트랙션을 등록하고 HTTP 201을 받는다")
    void createAttraction_admin_returnsCreated() throws Exception {
        // given
        CreateAttractionRequest request =
                new CreateAttractionRequest("롤러코스터", "빠른 놀이기구", "A구역", 10, 5);

        when(attractionService.createAttraction(ADMIN_ID, request))
                .thenReturn(new CreateAttractionResponse(
                        1L, "롤러코스터", "빠른 놀이기구", "A구역", 10, 5,
                        OperationStatus.OPERATING,
                        LocalDateTime.of(2026, 11, 2, 10, 0)
                ));

        // when & then
        mockMvc.perform(post("/api/admin/attractions")
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"롤러코스터","description":"빠른 놀이기구","location":"A구역",
                                 "durationMinutes":10,"entranceMoveMinutes":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("어트랙션 등록에 성공했습니다."))
                .andExpect(jsonPath("$.data.attractionId").value(1))
                .andExpect(jsonPath("$.data.operationStatus").value("OPERATING"));

        // 로그인한 관리자 ID가 서비스까지 전달됐는지 확인
        verify(attractionService).createAttraction(ADMIN_ID, request);
    }

    @Test
    @DisplayName("관리자는 어트랙션 정보를 수정할 수 있다")
    void updateAttraction_admin_returnsOk() throws Exception {
        // given
        UpdateAttractionRequest request = new UpdateAttractionRequest("새이름", null, null, null, null);

        when(attractionService.updateAttraction(ADMIN_ID, 1L, request))
                .thenReturn(new SearchAttractionResponse(
                        1L, "새이름", "빠른 놀이기구", "A구역", 10, 5,
                        OperationStatus.OPERATING,
                        LocalDateTime.of(2026, 11, 2, 10, 0),
                        LocalDateTime.of(2026, 11, 2, 11, 0)
                ));

        // when & then
        mockMvc.perform(patch("/api/admin/attractions/{attractionId}", 1L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"새이름"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("어트랙션 수정에 성공했습니다."))
                .andExpect(jsonPath("$.data.name").value("새이름"));

        verify(attractionService).updateAttraction(ADMIN_ID, 1L, request);
    }

    @Test
    @DisplayName("관리자는 어트랙션 운영 상태를 변경할 수 있다")
    void updateAttractionStatus_admin_returnsOk() throws Exception {
        // given
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest("MAINTENANCE");

        when(attractionService.updateAttractionStatus(ADMIN_ID, 1L, request))
                .thenReturn(new UpdateAttractionStatusResponse(
                        1L,
                        OperationStatus.OPERATING,
                        OperationStatus.MAINTENANCE,
                        LocalDateTime.of(2026, 11, 2, 11, 0)
                ));

        // when & then
        mockMvc.perform(patch("/api/admin/attractions/{attractionId}/status", 1L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operationStatus":"MAINTENANCE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("어트랙션 운영 상태 변경에 성공했습니다."))
                .andExpect(jsonPath("$.data.previousStatus").value("OPERATING"))
                .andExpect(jsonPath("$.data.operationStatus").value("MAINTENANCE"));

        verify(attractionService).updateAttractionStatus(ADMIN_ID, 1L, request);
    }

    @Test
    @DisplayName("관리자는 어트랙션을 삭제할 수 있다")
    void deleteAttraction_admin_returnsOk() throws Exception {
        // when & then (반환값이 없는 메서드라 given이 필요 없다)
        mockMvc.perform(delete("/api/admin/attractions/{attractionId}", 1L)
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("어트랙션 삭제에 성공했습니다."));

        verify(attractionService).deleteAttraction(ADMIN_ID, 1L);
    }

    @Test
    @DisplayName("비로그인 요청은 HTTP 401을 반환하고 서비스를 호출하지 않는다")
    void deleteAttraction_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/admin/attractions/{attractionId}", 1L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(attractionService);
    }

    @Test
    @DisplayName("일반 회원 요청은 HTTP 403을 반환하고 서비스를 호출하지 않는다")
    void deleteAttraction_user_returnsForbidden() throws Exception {
        mockMvc.perform(delete("/api/admin/attractions/{attractionId}", 1L)
                        .with(authenticatedAs("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(attractionService);
    }

    private RequestPostProcessor authenticatedAs(String role) {
        // 실제 JWT 대신 "ID 1번, 이 역할로 로그인한 사람"이라는 인증 정보를 넣는다.
        return authentication(new UsernamePasswordAuthenticationToken(
                ADMIN_ID,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        ));
    }
}
