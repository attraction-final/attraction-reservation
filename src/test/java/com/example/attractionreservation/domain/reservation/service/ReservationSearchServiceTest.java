package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSearchServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    private static final Long USER_ID = 1L;

    private final Pageable pageable = PageRequest.of(
            0,
            10,
            Sort.by(
                    Sort.Order.desc("status"),
                    Sort.Order.desc("id")
            )
    );

    @Test
    @DisplayName("검색 조건이 없으면 날짜 경계와 상태를 null로 전달한다")
    void findReservationAll_withoutCondition() {
        // given
        SearchReservationCondition condition =
                new SearchReservationCondition(null, null);

        when(reservationRepository.findReservationAllByCondition(
                USER_ID, null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchReservationResponse> result =
                reservationService.findReservationAll(
                        USER_ID, pageable, condition
                );

        // then
        assertThat(result.getContent()).isEmpty();

        verify(reservationRepository).findReservationAllByCondition(
                USER_ID, null, null, null, pageable
        );
    }

    @Test
    @DisplayName("상태만 입력하면 날짜 필터 없이 상태 조건을 전달한다")
    void findReservationAll_withStatus() {
        // given
        SearchReservationCondition condition =
                new SearchReservationCondition(
                        ReservationStatus.RESERVED,
                        null
                );

        when(reservationRepository.findReservationAllByCondition(
                USER_ID,
                ReservationStatus.RESERVED,
                null,
                null,
                pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchReservationResponse> result =
                reservationService.findReservationAll(
                        USER_ID, pageable, condition
                );

        // then
        assertThat(result.getContent()).isEmpty();

        verify(reservationRepository).findReservationAllByCondition(
                USER_ID,
                ReservationStatus.RESERVED,
                null,
                null,
                pageable
        );
    }

    @Test
    @DisplayName("날짜만 입력하면 당일 자정과 다음 날 자정을 전달한다")
    void findReservationAll_withDate() {
        // given
        LocalDate date = LocalDate.of(2026, 10, 23);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime nextDayStart = date.plusDays(1).atStartOfDay();

        SearchReservationCondition condition =
                new SearchReservationCondition(null, date);

        when(reservationRepository.findReservationAllByCondition(
                USER_ID, null, dayStart, nextDayStart, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchReservationResponse> result =
                reservationService.findReservationAll(
                        USER_ID, pageable, condition
                );

        // then
        assertThat(result.getContent()).isEmpty();

        verify(reservationRepository).findReservationAllByCondition(
                USER_ID, null, dayStart, nextDayStart, pageable
        );
    }

    @Test
    @DisplayName("상태와 날짜를 함께 입력하면 두 조건을 모두 전달한다")
    void findReservationAll_withStatusAndDate() {
        // given
        // 다음 날 경계가 다음 연도로 넘어가는 경우도 확인한다.
        LocalDate date = LocalDate.of(2026, 12, 31);
        LocalDateTime dayStart =
                LocalDateTime.of(2026, 12, 31, 0, 0);
        LocalDateTime nextDayStart =
                LocalDateTime.of(2027, 1, 1, 0, 0);

        SearchReservationCondition condition =
                new SearchReservationCondition(
                        ReservationStatus.RESERVED,
                        date
                );

        when(reservationRepository.findReservationAllByCondition(
                USER_ID,
                ReservationStatus.RESERVED,
                dayStart,
                nextDayStart,
                pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchReservationResponse> result =
                reservationService.findReservationAll(
                        USER_ID, pageable, condition
                );

        // then
        assertThat(result.getContent()).isEmpty();

        verify(reservationRepository).findReservationAllByCondition(
                USER_ID,
                ReservationStatus.RESERVED,
                dayStart,
                nextDayStart,
                pageable
        );
    }

    @Test
    @DisplayName("예약을 응답 DTO로 변환하고 페이지 정보를 유지한다")
    void findReservationAll_mapsResponseAndPreservesPage() {
        // given
        SearchReservationCondition condition =
                new SearchReservationCondition(null, null);

        Pageable secondPage = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "id")
        );

        Reservation reservation = mock(Reservation.class);
        AttractionSlot slot = mock(AttractionSlot.class);
        Attraction attraction = mock(Attraction.class);
        Ticket ticket = mock(Ticket.class);

        LocalDateTime startsAt =
                LocalDateTime.of(2026, 10, 23, 16, 0);
        LocalDateTime endsAt = startsAt.plusMinutes(10);
        Instant createdAt = Instant.parse("2026-10-23T01:00:00Z");

        when(reservation.getId()).thenReturn(100L);
        when(reservation.getReservationNumber()).thenReturn("RSV-test");
        when(reservation.getAttractionSlot()).thenReturn(slot);
        when(reservation.getTicket()).thenReturn(ticket);
        when(reservation.getStatus()).thenReturn(ReservationStatus.RESERVED);
        when(reservation.getCreatedAt()).thenReturn(createdAt);

        when(slot.getId()).thenReturn(20L);
        when(slot.getAttraction()).thenReturn(attraction);
        when(slot.getStartsAt()).thenReturn(startsAt);
        when(slot.getEndsAt()).thenReturn(endsAt);
        when(attraction.getName()).thenReturn("롤러코스터");
        when(ticket.getId()).thenReturn(10L);

        Page<Reservation> reservations = new PageImpl<>(
                List.of(reservation),
                secondPage,
                3
        );

        when(reservationRepository.findReservationAllByCondition(
                USER_ID, null, null, null, secondPage
        )).thenReturn(reservations);

        // when
        Page<SearchReservationResponse> result =
                reservationService.findReservationAll(
                        USER_ID, secondPage, condition
                );

        // then
        assertThat(result.getContent()).hasSize(1);

        SearchReservationResponse response = result.getContent().get(0);

        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.reservationNumber()).isEqualTo("RSV-test");
        assertThat(response.slotId()).isEqualTo(20L);
        assertThat(response.attractionName()).isEqualTo("롤러코스터");
        assertThat(response.ticketId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.startsAt()).isEqualTo(startsAt);
        assertThat(response.endsAt()).isEqualTo(endsAt);
        assertThat(response.createdAt()).isEqualTo(createdAt);

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getSort()).isEqualTo(secondPage.getSort());
    }
}