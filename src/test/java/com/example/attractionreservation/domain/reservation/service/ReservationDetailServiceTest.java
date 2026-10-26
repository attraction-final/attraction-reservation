package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationDetailResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
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
class ReservationDetailServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 100L;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("본인 예약의 상세 정보를 반환한다")
    void findReservationById_success() {
        // given
        Reservation reservation = createReservation(
                ReservationStatus.RESERVED,
                null,
                null
        );

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        // when
        SearchReservationDetailResponse response =
                reservationService.findReservationById(
                        USER_ID,
                        RESERVATION_ID
                );

        // then
        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.reservationNumber()).isEqualTo("RSV-test");
        assertThat(response.attractionId()).isEqualTo(3L);
        assertThat(response.attractionName()).isEqualTo("롤러코스터");
        assertThat(response.ticketId()).isEqualTo(10L);
        assertThat(response.slotId()).isEqualTo(20L);
        assertThat(response.startsAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 16, 0));
        assertThat(response.endsAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 24, 16, 10));
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.createdAt())
                .isEqualTo(Instant.parse("2026-10-24T01:00:00Z"));
        assertThat(response.cancelReason()).isNull();
        assertThat(response.cancelledAt()).isNull();

        verify(reservationRepository).findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        );
    }

    @Test
    @DisplayName("취소된 본인 예약은 취소 사유와 취소 시각을 반환한다")
    void findReservationById_cancelledReservation() {
        // given
        Instant cancelledAt = Instant.parse("2026-10-24T02:00:00Z");
        ReservationCancelReason cancelReason =
                ReservationCancelReason.ATTRACTION_MAINTENANCE;

        Reservation reservation = createReservation(
                ReservationStatus.CANCELLED,
                cancelReason,
                cancelledAt
        );

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        // when
        SearchReservationDetailResponse response =
                reservationService.findReservationById(
                        USER_ID,
                        RESERVATION_ID
                );

        // then
        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelReason()).isEqualTo(cancelReason);
        assertThat(response.cancelledAt()).isEqualTo(cancelledAt);

        verify(reservationRepository).findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        );
    }

    @Test
    @DisplayName("예약이 없으면 예약 조회 실패 예외를 반환한다")
    void findReservationById_notFound() {
        // given
        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.findReservationById(
                        USER_ID,
                        RESERVATION_ID
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verify(reservationRepository).findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        );
    }

    @Test
    @DisplayName("다른 회원 ID로 조회한 결과가 없으면 동일한 조회 실패 예외를 반환한다")
    void findReservationById_otherUserNotFound() {
        // given
        Long otherUserId = 2L;

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                otherUserId
        )).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.findReservationById(
                        otherUserId,
                        RESERVATION_ID
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verify(reservationRepository).findReservationByIdAndUser_Id(
                RESERVATION_ID,
                otherUserId
        );
    }

    private Reservation createReservation(
            ReservationStatus status,
            ReservationCancelReason cancelReason,
            Instant cancelledAt
    ) {
        Reservation reservation = mock(Reservation.class);
        AttractionSlot slot = mock(AttractionSlot.class);
        Attraction attraction = mock(Attraction.class);
        Ticket ticket = mock(Ticket.class);

        when(reservation.getId()).thenReturn(RESERVATION_ID);
        when(reservation.getReservationNumber()).thenReturn("RSV-test");
        when(reservation.getAttractionSlot()).thenReturn(slot);
        when(reservation.getTicket()).thenReturn(ticket);
        when(reservation.getStatus()).thenReturn(status);
        when(reservation.getCreatedAt())
                .thenReturn(Instant.parse("2026-10-24T01:00:00Z"));
        when(reservation.getCancelReason()).thenReturn(cancelReason);
        when(reservation.getCancelledAt()).thenReturn(cancelledAt);

        when(slot.getId()).thenReturn(20L);
        when(slot.getAttraction()).thenReturn(attraction);
        when(slot.getStartsAt())
                .thenReturn(LocalDateTime.of(2026, 10, 24, 16, 0));
        when(slot.getEndsAt())
                .thenReturn(LocalDateTime.of(2026, 10, 24, 16, 10));

        when(attraction.getId()).thenReturn(3L);
        when(attraction.getName()).thenReturn("롤러코스터");
        when(ticket.getId()).thenReturn(10L);

        return reservation;
    }
}