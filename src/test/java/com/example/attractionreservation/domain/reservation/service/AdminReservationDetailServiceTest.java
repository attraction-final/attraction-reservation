package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationDetailResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminReservationDetailServiceTest {

    private static final Long RESERVATION_ID = 100L;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("관리자 예약 상세 조회 시 회원과 예약 정보를 반환한다")
    void findAdminReservationById_success() {
        // given
        Reservation reservation = createReservation(
                ReservationStatus.RESERVED,
                null,
                null
        );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when
        SearchAdminReservationDetailResponse response =
                reservationService.findAdminReservationById(RESERVATION_ID);

        // then
        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.reservationNumber()).isEqualTo("RSV-test");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.ticketId()).isEqualTo(10L);
        assertThat(response.attractionId()).isEqualTo(3L);
        assertThat(response.attractionName()).isEqualTo("롤러코스터");
        assertThat(response.slotId()).isEqualTo(20L);
        assertThat(response.startsAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 16, 0));
        assertThat(response.endsAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 16, 10));
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.createdAt())
                .isEqualTo(Instant.parse("2026-10-24T01:00:00Z"));
        assertThat(response.cancelledAt()).isNull();
        assertThat(response.cancelReason()).isNull();

        verify(reservationRepository).findById(RESERVATION_ID);
    }

    @Test
    @DisplayName("취소된 예약은 취소 시각과 사유를 반환한다")
    void findAdminReservationById_cancelledReservation() {
        // given
        Instant cancelledAt = Instant.parse("2026-10-24T02:00:00Z");
        String cancelReason = "어트랙션 긴급 점검";

        Reservation reservation = createReservation(
                ReservationStatus.CANCELLED,
                cancelledAt,
                cancelReason
        );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when
        SearchAdminReservationDetailResponse response =
                reservationService.findAdminReservationById(RESERVATION_ID);

        // then
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelledAt()).isEqualTo(cancelledAt);
        assertThat(response.cancelReason()).isEqualTo(cancelReason);

        verify(reservationRepository).findById(RESERVATION_ID);
    }

    @Test
    @DisplayName("존재하지 않는 예약이면 조회 실패 예외를 반환한다")
    void findAdminReservationById_notFound() {
        // given
        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.findAdminReservationById(RESERVATION_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verify(reservationRepository).findById(RESERVATION_ID);
    }

    private Reservation createReservation(
            ReservationStatus status,
            Instant cancelledAt,
            String cancelReason
    ) {
        Reservation reservation = mock(Reservation.class);
        User user = mock(User.class);
        Ticket ticket = mock(Ticket.class);
        AttractionSlot slot = mock(AttractionSlot.class);
        Attraction attraction = mock(Attraction.class);

        when(reservation.getId()).thenReturn(RESERVATION_ID);
        when(reservation.getReservationNumber()).thenReturn("RSV-test");
        when(reservation.getUser()).thenReturn(user);
        when(reservation.getTicket()).thenReturn(ticket);
        when(reservation.getAttractionSlot()).thenReturn(slot);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.getCreatedAt())
                .thenReturn(Instant.parse("2026-10-24T01:00:00Z"));
        when(reservation.getCancelledAt()).thenReturn(cancelledAt);
        when(reservation.getCancelReason()).thenReturn(cancelReason);

        when(user.getId()).thenReturn(1L);
        when(ticket.getId()).thenReturn(10L);
        when(slot.getId()).thenReturn(20L);
        when(slot.getAttraction()).thenReturn(attraction);
        when(slot.getStartsAt())
                .thenReturn(LocalDateTime.of(2026, 10, 24, 16, 0));
        when(slot.getEndsAt())
                .thenReturn(LocalDateTime.of(2026, 10, 24, 16, 10));

        when(attraction.getId()).thenReturn(3L);
        when(attraction.getName()).thenReturn("롤러코스터");

        return reservation;
    }
}