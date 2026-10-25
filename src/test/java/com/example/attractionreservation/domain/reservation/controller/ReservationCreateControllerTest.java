package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReservationCreateControllerTest {

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReservationController(reservationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("예약 생성에 성공하면 201과 예약 정보를 반환한다")
    void createReservation_success() throws Exception {
        // given
        CreateReservationRequest request =
                new CreateReservationRequest(10L, 20L);

        CreateReservationResponse response =
                new CreateReservationResponse(
                        100L,
                        "RSV-test-number",
                        10L,
                        20L,
                        "롤러코스터",
                        ReservationStatus.RESERVED,
                        LocalDateTime.of(2026, 10, 25, 16, 0),
                        LocalDateTime.of(2026, 10, 25, 16, 10),
                        Instant.parse("2026-10-25T05:00:00Z")
                );

        when(reservationService.createReservation(1L, request))
                .thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketId": 10,
                                  "slotId": 20
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("어트랙션 예약에 성공했습니다."))
                .andExpect(jsonPath("$.data.reservationId").value(100))
                .andExpect(jsonPath("$.data.reservationNumber")
                        .value("RSV-test-number"))
                .andExpect(jsonPath("$.data.ticketId").value(10))
                .andExpect(jsonPath("$.data.slotId").value(20))
                .andExpect(jsonPath("$.data.attractionName")
                        .value("롤러코스터"))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.startsAt").exists())
                .andExpect(jsonPath("$.data.endsAt").exists())
                .andExpect(jsonPath("$.data.createdAt").exists());

        // then
        verify(reservationService).createReservation(1L, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"slotId\":20}",
            "{\"ticketId\":null,\"slotId\":20}",
            "{\"ticketId\":0,\"slotId\":20}",
            "{\"ticketId\":-1,\"slotId\":20}",
            "{\"ticketId\":10}",
            "{\"ticketId\":10,\"slotId\":null}",
            "{\"ticketId\":10,\"slotId\":0}",
            "{\"ticketId\":10,\"slotId\":-1}"
    })
    @DisplayName("필수 ID가 없거나 1 미만이면 400을 반환하고 Service를 호출하지 않는다")
    void createReservation_invalidId(String requestBody) throws Exception {
        // given
        String url = "/api/reservations";

        // when & then
        mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        // then
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("존재하지 않는 티켓으로 예약하면 404와 오류 응답을 반환한다")
    void createReservation_ticketNotFound() throws Exception {
        // given
        CreateReservationRequest request =
                new CreateReservationRequest(999L, 20L);

        when(reservationService.createReservation(1L, request))
                .thenThrow(new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticketId": 999,
                                  "slotId": 20
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.TICKET_NOT_FOUND.getMessage()));

        // then
        verify(reservationService).createReservation(1L, request);
    }
}