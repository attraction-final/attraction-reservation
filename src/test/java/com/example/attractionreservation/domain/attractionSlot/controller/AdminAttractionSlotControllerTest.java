package com.example.attractionreservation.domain.attractionSlot.controller;

import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotResponse;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotCapacityRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotCapacityResponse;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotStatusRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotStatusResponse;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeResponse;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
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
@WebMvcTest(AdminAttractionSlotController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class AdminAttractionSlotControllerTest {

    private static final Long ADMIN_ID = 1L; // authenticatedAs()가 넣는 로그인 ID

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 12, 1, 14, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 12, 1, 14, 10);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AttractionSlotService attractionSlotService;

    // 보안 필터가 필요로 하는 빈 (AdminUserControllerTest와 동일)
    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("관리자는 슬롯을 등록하고 HTTP 201을 받는다")
    void createAttractionSlot_admin_returnsCreated() throws Exception {
        // given
        CreateAttractionSlotRequest request = new CreateAttractionSlotRequest(STARTS_AT, 20);

        when(attractionSlotService.createAttractionSlot(ADMIN_ID, 1L, request))
                .thenReturn(new CreateAttractionSlotResponse(
                        31L, 1L, STARTS_AT, ENDS_AT, 20, 0, 20, SlotStatus.OPEN));

        // when & then
        mockMvc.perform(post("/api/admin/attractions/{attractionId}/slots", 1L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"2026-12-01T14:00:00","capacity":20}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("슬롯 등록에 성공했습니다."))
                .andExpect(jsonPath("$.data.slotId").value(31))
                .andExpect(jsonPath("$.data.startsAt").value("2026-12-01T14:00:00"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        // 로그인한 관리자 ID가 서비스까지 전달됐는지 확인
        verify(attractionSlotService).createAttractionSlot(ADMIN_ID, 1L, request);
    }

    @Test
    @DisplayName("관리자는 슬롯 시간을 수정할 수 있다")
    void updateAttractionSlotTime_admin_returnsOk() throws Exception {
        // given
        UpdateAttractionSlotTimeRequest request = new UpdateAttractionSlotTimeRequest(STARTS_AT);

        when(attractionSlotService.updateAttractionSlotTime(ADMIN_ID, 31L, request))
                .thenReturn(new UpdateAttractionSlotTimeResponse(
                        31L, 1L, STARTS_AT, ENDS_AT, 20, 0, 20, SlotStatus.OPEN));

        // when & then
        mockMvc.perform(patch("/api/admin/slots/{slotId}/time", 31L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startsAt":"2026-12-01T14:00:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("슬롯 시간 수정에 성공했습니다."))
                .andExpect(jsonPath("$.data.endsAt").value("2026-12-01T14:10:00"));

        verify(attractionSlotService).updateAttractionSlotTime(ADMIN_ID, 31L, request);
    }

    @Test
    @DisplayName("관리자는 슬롯 정원을 수정할 수 있다")
    void updateAttractionSlotCapacity_admin_returnsOk() throws Exception {
        // given
        UpdateAttractionSlotCapacityRequest request = new UpdateAttractionSlotCapacityRequest(30);

        when(attractionSlotService.updateAttractionSlotCapacity(ADMIN_ID, 31L, request))
                .thenReturn(new UpdateAttractionSlotCapacityResponse(31L, 30, 5, 25, SlotStatus.OPEN));

        // when & then
        mockMvc.perform(patch("/api/admin/slots/{slotId}/capacity", 31L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"capacity":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("슬롯 정원 수정에 성공했습니다."))
                .andExpect(jsonPath("$.data.capacity").value(30))
                .andExpect(jsonPath("$.data.remainingCount").value(25));

        verify(attractionSlotService).updateAttractionSlotCapacity(ADMIN_ID, 31L, request);
    }

    @Test
    @DisplayName("관리자는 슬롯 상태를 변경할 수 있다")
    void updateAttractionSlotStatus_admin_returnsOk() throws Exception {
        // given
        UpdateAttractionSlotStatusRequest request = new UpdateAttractionSlotStatusRequest("CLOSED");

        when(attractionSlotService.updateAttractionSlotStatus(ADMIN_ID, 31L, request))
                .thenReturn(new UpdateAttractionSlotStatusResponse(
                        31L, SlotStatus.OPEN, SlotStatus.CLOSED, 0));

        // when & then
        mockMvc.perform(patch("/api/admin/slots/{slotId}/status", 31L)
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLOSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("슬롯 상태 변경에 성공했습니다."))
                .andExpect(jsonPath("$.data.previousStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        verify(attractionSlotService).updateAttractionSlotStatus(ADMIN_ID, 31L, request);
    }

    @Test
    @DisplayName("관리자는 슬롯을 삭제(취소)할 수 있다")
    void deleteAttractionSlot_admin_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/admin/slots/{slotId}", 31L)
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("슬롯 삭제에 성공했습니다."));

        verify(attractionSlotService).deleteAttractionSlot(ADMIN_ID, 31L);
    }

    @Test
    @DisplayName("비로그인 요청은 HTTP 401을 반환하고 서비스를 호출하지 않는다")
    void deleteAttractionSlot_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/admin/slots/{slotId}", 31L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(attractionSlotService);
    }

    @Test
    @DisplayName("일반 회원 요청은 HTTP 403을 반환하고 서비스를 호출하지 않는다")
    void deleteAttractionSlot_user_returnsForbidden() throws Exception {
        mockMvc.perform(delete("/api/admin/slots/{slotId}", 31L)
                        .with(authenticatedAs("USER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(attractionSlotService);
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
