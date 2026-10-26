package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.user.entity.User;
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
class AdminReservationSearchServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    private final Pageable pageable = PageRequest.of(
            0,
            10,
            Sort.by(
                    Sort.Order.desc("status"),
                    Sort.Order.desc("id")
            )
    );

    @Test
    @DisplayName("검색 조건이 없으면 모든 필터를 null로 전달한다")
    void findAdminReservationAll_withoutCondition() {
        // given
        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        null, null, null, null
                );

        when(reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchAdminReservationResponse> result =
                reservationService.findAdminReservationAll(
                        condition,
                        pageable
                );

        // then
        assertThat(result.getContent()).isEmpty();

        verify(reservationRepository).findAdminReservationByCondition(
                null, null, null, null, null, pageable
        );
    }

    @Test
    @DisplayName("회원 ID 조건을 전달한다")
    void findAdminReservationAll_withUserId() {
        // given
        Long userId = 1L;

        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        userId, null, null, null
                );

        when(reservationRepository.findAdminReservationByCondition(
                userId, null, null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        reservationService.findAdminReservationAll(condition, pageable);

        // then
        verify(reservationRepository).findAdminReservationByCondition(
                userId, null, null, null, null, pageable
        );
    }

    @Test
    @DisplayName("어트랙션 ID 조건을 전달한다")
    void findAdminReservationAll_withAttractionId() {
        // given
        Long attractionId = 3L;

        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        null, attractionId, null, null
                );

        when(reservationRepository.findAdminReservationByCondition(
                null, attractionId, null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        reservationService.findAdminReservationAll(condition, pageable);

        // then
        verify(reservationRepository).findAdminReservationByCondition(
                null, attractionId, null, null, null, pageable
        );
    }

    @Test
    @DisplayName("예약 상태 조건을 전달한다")
    void findAdminReservationAll_withStatus() {
        // given
        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        null, null, ReservationStatus.RESERVED, null
                );

        when(reservationRepository.findAdminReservationByCondition(
                null,
                null,
                ReservationStatus.RESERVED,
                null,
                null,
                pageable
        )).thenReturn(Page.empty(pageable));

        // when
        reservationService.findAdminReservationAll(condition, pageable);

        // then
        verify(reservationRepository).findAdminReservationByCondition(
                null,
                null,
                ReservationStatus.RESERVED,
                null,
                null,
                pageable
        );
    }

    @Test
    @DisplayName("날짜를 당일 자정과 다음 날 자정으로 변환한다")
    void findAdminReservationAll_withDate() {
        // given
        LocalDate date = LocalDate.of(2026, 10, 24);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime nextDayStart = date.plusDays(1).atStartOfDay();

        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        null, null, null, date
                );

        when(reservationRepository.findAdminReservationByCondition(
                null, null, null, dayStart, nextDayStart, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        reservationService.findAdminReservationAll(condition, pageable);

        // then
        verify(reservationRepository).findAdminReservationByCondition(
                null, null, null, dayStart, nextDayStart, pageable
        );
    }

    @Test
    @DisplayName("모든 검색 조건과 연말 날짜 경계를 전달한다")
    void findAdminReservationAll_withAllConditions() {
        // given
        Long userId = 1L;
        Long attractionId = 3L;
        LocalDate date = LocalDate.of(2026, 12, 31);

        LocalDateTime dayStart =
                LocalDateTime.of(2026, 12, 31, 0, 0);
        LocalDateTime nextDayStart =
                LocalDateTime.of(2027, 1, 1, 0, 0);

        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        userId,
                        attractionId,
                        ReservationStatus.RESERVED,
                        date
                );

        when(reservationRepository.findAdminReservationByCondition(
                userId,
                attractionId,
                ReservationStatus.RESERVED,
                dayStart,
                nextDayStart,
                pageable
        )).thenReturn(Page.empty(pageable));

        // when
        reservationService.findAdminReservationAll(condition, pageable);

        // then
        verify(reservationRepository).findAdminReservationByCondition(
                userId,
                attractionId,
                ReservationStatus.RESERVED,
                dayStart,
                nextDayStart,
                pageable
        );
    }

    @Test
    @DisplayName("예약을 관리자 응답 DTO로 변환하고 페이지 정보를 유지한다")
    void findAdminReservationAll_mapsResponseAndPreservesPage() {
        // given
        SearchAdminReservationCondition condition =
                new SearchAdminReservationCondition(
                        null, null, null, null
                );

        Pageable secondPage = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.DESC, "id")
        );

        Reservation reservation = mock(Reservation.class);
        User user = mock(User.class);
        AttractionSlot slot = mock(AttractionSlot.class);
        Attraction attraction = mock(Attraction.class);

        LocalDateTime startsAt =
                LocalDateTime.of(2026, 10, 24, 16, 0);
        LocalDateTime endsAt = startsAt.plusMinutes(10);
        Instant createdAt = Instant.parse("2026-10-24T01:00:00Z");

        when(reservation.getId()).thenReturn(100L);
        when(reservation.getReservationNumber()).thenReturn("RSV-test");
        when(reservation.getUser()).thenReturn(user);
        when(reservation.getAttractionSlot()).thenReturn(slot);
        when(reservation.getStatus()).thenReturn(ReservationStatus.RESERVED);
        when(reservation.getCreatedAt()).thenReturn(createdAt);

        when(user.getId()).thenReturn(1L);
        when(slot.getId()).thenReturn(20L);
        when(slot.getAttraction()).thenReturn(attraction);
        when(slot.getStartsAt()).thenReturn(startsAt);
        when(slot.getEndsAt()).thenReturn(endsAt);
        when(attraction.getId()).thenReturn(3L);
        when(attraction.getName()).thenReturn("롤러코스터");

        Page<Reservation> reservations = new PageImpl<>(
                List.of(reservation),
                secondPage,
                3
        );

        when(reservationRepository.findAdminReservationByCondition(
                null, null, null, null, null, secondPage
        )).thenReturn(reservations);

        // when
        Page<SearchAdminReservationResponse> result =
                reservationService.findAdminReservationAll(
                        condition,
                        secondPage
                );

        // then
        assertThat(result.getContent()).hasSize(1);

        SearchAdminReservationResponse response =
                result.getContent().get(0);

        assertThat(response.reservationId()).isEqualTo(100L);
        assertThat(response.reservationNumber()).isEqualTo("RSV-test");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.attractionId()).isEqualTo(3L);
        assertThat(response.attractionName()).isEqualTo("롤러코스터");
        assertThat(response.slotId()).isEqualTo(20L);
        assertThat(response.startsAt()).isEqualTo(startsAt);
        assertThat(response.endsAt()).isEqualTo(endsAt);
        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(response.createdAt()).isEqualTo(createdAt);

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getSort()).isEqualTo(secondPage.getSort());
    }
}