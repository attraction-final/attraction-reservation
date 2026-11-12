package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.UpdateReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReservationCancelControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long RESERVATION_ID = 20L;

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null)
        );
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReservationController(reservationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("로그인 사용자와 예약 ID를 전달하고 취소 성공 응답을 반환한다")
    void cancelReservation_ValidRequest_ReturnsSuccess() throws Exception {
        // given
        Instant cancelledAt = Instant.parse("2026-10-25T04:30:00Z");
        UpdateReservationCancelResponse response = new UpdateReservationCancelResponse(
                RESERVATION_ID, "RSV-test-number", ReservationStatus.CANCELLED, cancelledAt
        );
        when(reservationService.cancelReservation(USER_ID, RESERVATION_ID)).thenReturn(response);

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", RESERVATION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("예약 취소에 성공했습니다."))
                .andExpect(jsonPath("$.data.reservationId").value(20))
                .andExpect(jsonPath("$.data.reservationNumber").value("RSV-test-number"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").exists());
        verify(reservationService).cancelReservation(USER_ID, RESERVATION_ID);
    }

    @Test
    @DisplayName("본인 예약 조회 결과가 없으면 404와 공통 오류 응답을 반환한다")
    void cancelReservation_NotFound_ReturnsNotFound() throws Exception {
        // given
        when(reservationService.cancelReservation(USER_ID, RESERVATION_ID))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", RESERVATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND_RESERVATION.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(reservationService).cancelReservation(USER_ID, RESERVATION_ID);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "RESERVATION_CANCEL_NOT_ALLOWED_STATUS", "RESERVATION_CANCEL_DEADLINE_PASSED"
    })
    @DisplayName("취소 불가 상태 또는 마감 초과이면 409와 공통 오류 응답을 반환한다")
    void cancelReservation_NotAllowed_ReturnsConflict(ErrorCode errorCode) throws Exception {
        // given
        when(reservationService.cancelReservation(USER_ID, RESERVATION_ID))
                .thenThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", RESERVATION_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(reservationService).cancelReservation(USER_ID, RESERVATION_ID);
    }

    @Test
    @DisplayName("숫자가 아닌 예약 ID는 400을 반환하고 Service를 호출하지 않는다")
    void cancelReservation_InvalidId_ReturnsBadRequest() throws Exception {
        // when & then
        mockMvc.perform(patch("/api/reservations/{reservationId}/cancel", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.getMessage()));
        verifyNoInteractions(reservationService);
    }
}
