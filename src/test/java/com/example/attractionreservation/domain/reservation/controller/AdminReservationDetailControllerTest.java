package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationDetailResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
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

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminReservationDetailControllerTest {

    private static final Long RESERVATION_ID = 100L;

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminReservationController(reservationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("예약 ID를 전달하고 관리자 예약 상세 응답을 반환한다")
    void getAdminReservationById_success() throws Exception {
        // given
        SearchAdminReservationDetailResponse response =
                new SearchAdminReservationDetailResponse(
                        RESERVATION_ID,
                        "RSV-test",
                        1L,
                        10L,
                        3L,
                        "롤러코스터",
                        20L,
                        LocalDateTime.of(2026, 10, 24, 16, 0),
                        LocalDateTime.of(2026, 10, 24, 16, 10),
                        ReservationStatus.CANCELLED,
                        Instant.parse("2026-10-24T01:00:00Z"),
                        Instant.parse("2026-10-24T02:00:00Z"),
                        ReservationCancelReason.ATTRACTION_MAINTENANCE,
                        "어트랙션 긴급 점검"
                );

        when(reservationService.findAdminReservationById(RESERVATION_ID))
                .thenReturn(response);

        // when & then
        mockMvc.perform(get(
                        "/api/admin/reservations/{reservationId}",
                        RESERVATION_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("예약 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.reservationId").value(100))
                .andExpect(jsonPath("$.data.reservationNumber").value("RSV-test"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.ticketId").value(10))
                .andExpect(jsonPath("$.data.attractionId").value(3))
                .andExpect(jsonPath("$.data.attractionName").value("롤러코스터"))
                .andExpect(jsonPath("$.data.slotId").value(20))
                .andExpect(jsonPath("$.data.startsAt").exists())
                .andExpect(jsonPath("$.data.endsAt").exists())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.createdAt").exists())
                .andExpect(jsonPath("$.data.cancelledAt").exists())
                .andExpect(jsonPath("$.data.cancelReasonMessage")
                        .value("어트랙션 긴급 점검"));

        verify(reservationService).findAdminReservationById(RESERVATION_ID);
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 404와 공통 오류 응답을 반환한다")
    void getAdminReservationById_notFound() throws Exception {
        // given
        when(reservationService.findAdminReservationById(RESERVATION_ID))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        // when & then
        mockMvc.perform(get(
                        "/api/admin/reservations/{reservationId}",
                        RESERVATION_ID
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.NOT_FOUND_RESERVATION.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(reservationService).findAdminReservationById(RESERVATION_ID);
    }

    @Test
    @DisplayName("숫자가 아닌 예약 ID는 400을 반환하고 Service를 호출하지 않는다")
    void getAdminReservationById_invalidId() throws Exception {
        // given
        String invalidReservationId = "abc";

        // when & then
        mockMvc.perform(get(
                        "/api/admin/reservations/{reservationId}",
                        invalidReservationId
                ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.INVALID_REQUEST.getMessage()));

        verifyNoInteractions(reservationService);
    }
}
