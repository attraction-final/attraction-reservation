package com.example.attractionreservation.domain.ticket.controller;

import com.example.attractionreservation.domain.ticket.dto.SearchTicketDetailResponse;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TicketDetailControllerTest {

    private static final Long USER_ID = 100L;

    @Mock
    private TicketService ticketService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null)
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TicketController(ticketService))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("상세 조회에 성공하면 200과 티켓 정보를 반환한다")
    void getTicketById_ExistingTicket_ReturnsDetail() throws Exception {
        // given
        Long ticketId = 1L;
        Instant enteredAt = Instant.parse("2026-10-25T01:00:00Z");

        SearchTicketDetailResponse response =
                createResponse(ticketId, TicketStatus.IN_USE, enteredAt);

        when(ticketService.findTicketById(USER_ID, ticketId))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("티켓 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.ticketId").value(1))
                .andExpect(jsonPath("$.data.ticketCode")
                        .value("TKT-test-code"))
                .andExpect(jsonPath("$.data.ticketProductId").value(10))
                .andExpect(jsonPath("$.data.name")
                        .value("발급 당시 종일이용권"))
                .andExpect(jsonPath("$.data.type").value("ALL_DAY"))
                .andExpect(jsonPath("$.data.validDate").value("2026-10-25"))
                .andExpect(jsonPath("$.data.status").value("IN_USE"))
                .andExpect(jsonPath("$.data.enteredAt")
                        .value("2026-10-25T01:00:00Z"));

        verify(ticketService).findTicketById(USER_ID, ticketId);
    }

    @Test
    @DisplayName("입장 전 티켓도 상세 조회에 성공한다")
    void getTicketById_NotEntered_ReturnsSuccess() throws Exception {
        // given
        Long ticketId = 1L;

        when(ticketService.findTicketById(USER_ID, ticketId))
                .thenReturn(createResponse(
                        ticketId, TicketStatus.UNUSED, null
                ));

        // when & then
        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UNUSED"))
                .andExpect(jsonPath("$.data.enteredAt").doesNotExist());

        verify(ticketService).findTicketById(USER_ID, ticketId);
    }

    @Test
    @DisplayName("존재하지 않는 티켓은 404와 공통 오류 응답을 반환한다")
    void getTicketById_NotFound_ReturnsNotFound() throws Exception {
        // given
        Long ticketId = 999L;

        when(ticketService.findTicketById(USER_ID, ticketId))
                .thenThrow(new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.TICKET_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(ticketService).findTicketById(USER_ID, ticketId);
    }

    @Test
    @DisplayName("숫자가 아닌 티켓 ID는 400을 반환하고 Service를 호출하지 않는다")
    void getTicketById_InvalidId_ReturnsBadRequest() throws Exception {
        // when & then
        mockMvc.perform(get("/api/tickets/{ticketId}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REQUEST.getMessage()));

        verifyNoInteractions(ticketService);
    }

    private SearchTicketDetailResponse createResponse(
            Long ticketId,
            TicketStatus status,
            Instant enteredAt
    ) {
        return new SearchTicketDetailResponse(
                ticketId,
                "TKT-test-code",
                10L,
                "발급 당시 종일이용권",
                TicketType.ALL_DAY,
                LocalDate.of(2026, 10, 25),
                status,
                enteredAt
        );
    }
}