package com.example.attractionreservation.domain.reservation.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
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
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminReservationCommandControllerTest {

    private static final Long ADMIN_ID = 99L;
    private static final Long RESERVATION_ID = 100L;

    @Mock
    private ReservationService reservationService;

    private MockMvc mockMvc;
    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // 실제 principal resolver를 사용하되, Security filter chain의 권한 검증은 별도 테스트 대상이다.
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                ADMIN_ID, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));
        SecurityContextHolder.setContext(context);

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminReservationController(reservationService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        logger = (Logger) LoggerFactory.getLogger(AdminReservationController.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("관리자 강제 취소 성공 응답을 반환하고 예약 ID와 관리자 ID 및 취소 사유를 기록한다")
    void adminReservationCancel_Success_ReturnsResponseAndLogsAdminId() throws Exception {
        // given
        ReservationCancelReason reason = ReservationCancelReason.OPERATIONAL_ISSUE;
        UpdateAdminReservationCancelRequest request = new UpdateAdminReservationCancelRequest(reason);
        UpdateAdminReservationCancelResponse response = new UpdateAdminReservationCancelResponse(
                RESERVATION_ID, ReservationStatus.CANCELLED, Instant.parse("2026-10-31T05:00:00Z"),
                reason, reason.getMessage()
        );
        when(reservationService.adminReservationCancel(RESERVATION_ID, request)).thenReturn(response);

        // when
        var result = mockMvc.perform(patch("/api/admin/reservations/{reservationId}/cancel", RESERVATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cancelReason":"OPERATIONAL_ISSUE"}
                        """));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("예약 강제 취소에 성공했습니다."))
                .andExpect(jsonPath("$.data.reservationId").value(100))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelledAt").exists())
                .andExpect(jsonPath("$.data.cancelReason").value(reason.name()))
                .andExpect(jsonPath("$.data.cancelReasonMessage").value(reason.getMessage()));
        verify(reservationService).adminReservationCancel(RESERVATION_ID, request);
        verifyNoMoreInteractions(reservationService);
        assertInfoLog("event=RESERVATION_ADMIN_CANCELLED reservationId=100 userId=99 reason=\"OPERATIONAL_ISSUE\"");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ReservationStatus.class, names = {"COMPLETED", "NO_SHOW"})
    @DisplayName("관리자 완료 및 노쇼 처리 성공 응답을 반환하고 해당 상태의 이벤트와 관리자 ID를 기록한다")
    void updateReservationStatus_TerminalStatus_ReturnsResponseAndLogsMatchingEvent(
            ReservationStatus targetStatus
    ) throws Exception {
        // given
        UpdateAdminReservationStatusRequest request = new UpdateAdminReservationStatusRequest(targetStatus);
        UpdateAdminReservationStatusResponse response = new UpdateAdminReservationStatusResponse(
                RESERVATION_ID, "RSV-test", targetStatus, Instant.parse("2026-10-31T08:00:00Z")
        );
        when(reservationService.updateReservationStatus(RESERVATION_ID, request)).thenReturn(response);
        String expectedEvent = targetStatus == ReservationStatus.COMPLETED
                ? "RESERVATION_COMPLETED" : "RESERVATION_NO_SHOW_RECORDED";

        // when
        var result = mockMvc.perform(patch("/api/admin/reservations/{reservationId}/status", RESERVATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + targetStatus.name() + "\"}"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("예약 상태 변경에 성공했습니다."))
                .andExpect(jsonPath("$.data.reservationId").value(100))
                .andExpect(jsonPath("$.data.reservationNumber").value("RSV-test"))
                .andExpect(jsonPath("$.data.status").value(targetStatus.name()))
                .andExpect(jsonPath("$.data.updatedAt").exists());
        verify(reservationService).updateReservationStatus(RESERVATION_ID, request);
        verifyNoMoreInteractions(reservationService);
        assertInfoLog("event=" + expectedEvent + " reservationId=100 userId=99");
    }

    private void assertInfoLog(String expectedMessage) {
        assertThat(appender.list).singleElement().satisfies(logEvent -> {
            assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
            assertThat(logEvent.getFormattedMessage()).isEqualTo(expectedMessage);
            assertThat(logEvent.getThrowableProxy()).isNull();
        });
    }
}
