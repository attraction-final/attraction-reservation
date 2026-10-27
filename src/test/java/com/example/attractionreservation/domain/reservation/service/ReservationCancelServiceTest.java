package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.reservation.dto.UpdateReservationCancelResponse;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationCancelServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long SLOT_ID = 20L;

    private static final LocalDateTime STARTS_AT =
            LocalDateTime.of(2026, 10, 24, 16, 0);

    private static final LocalDateTime DEADLINE =
            STARTS_AT.minusMinutes(30);

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private AttractionSlotService attractionSlotService;

    @InjectMocks
    private ReservationService reservationService;

    @Test
    @DisplayName("본인 예약을 취소하고 슬롯 정원을 복구한다")
    void cancelReservation_success() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);
        when(slot.getStartsAt()).thenReturn(STARTS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        LocalDateTime currentTime = DEADLINE.minusMinutes(1);
        Instant expectedCancelledAt = currentTime.atZone(KstTime.ZONE).toInstant();

        // when
        UpdateReservationCancelResponse response =
                cancelAt(currentTime);

        // then
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt())
                .isEqualTo(expectedCancelledAt);

        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.reservationNumber())
                .isEqualTo(reservation.getReservationNumber());
        assertThat(response.status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(response.cancelledAt())
                .isEqualTo(reservation.getCancelledAt());

        verify(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("취소 검증과 취소 시각 기록에 동일한 순간을 사용한다")
    void cancelReservation_usesSameInstant() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);
        when(slot.getStartsAt()).thenReturn(STARTS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        Instant expectedCancelledAt = DEADLINE.atZone(KstTime.ZONE).toInstant();

        try (MockedStatic<KstTime> mocked = mockStatic(KstTime.class)) {
            mocked.when(KstTime::now).thenReturn(DEADLINE);

            // when
            reservationService.cancelReservation(USER_ID, RESERVATION_ID);

            // then
            mocked.verify(KstTime::now, times(1));
            assertThat(reservation.getCancelledAt()).isEqualTo(expectedCancelledAt);
        }
    }

    @Test
    @DisplayName("예약 취소 마감 정각에는 취소할 수 있다")
    void cancelReservation_atDeadline() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);
        when(slot.getStartsAt()).thenReturn(STARTS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        // when
        UpdateReservationCancelResponse response = cancelAt(DEADLINE);

        // then
        assertThat(response.status())
                .isEqualTo(ReservationStatus.CANCELLED);
        verify(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);
    }

    @Test
    @DisplayName("예약 취소 마감 정각을 지나면 취소할 수 없다")
    void cancelReservation_afterDeadline() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getStartsAt()).thenReturn(STARTS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                cancelAt(DEADLINE.plusNanos(1))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED
                        )
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.getCancelledAt()).isNull();
        verifyNoInteractions(attractionSlotService);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = "RESERVED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("예약 완료 상태가 아니면 취소할 수 없다")
    void cancelReservation_invalidStatus(ReservationStatus status) {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        Reservation reservation = createReservation(slot);

        ReflectionTestUtils.setField(reservation, "status", status);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                reservationService.cancelReservation(
                        USER_ID,
                        RESERVATION_ID
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS
                        )
        );

        assertThat(reservation.getStatus()).isEqualTo(status);
        assertThat(reservation.getCancelledAt()).isNull();
        verifyNoInteractions(attractionSlotService);
    }

    @Test
    @DisplayName("본인 예약 조회 결과가 없으면 취소하지 않는다")
    void cancelReservation_notFound() {
        // given
        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.cancelReservation(
                        USER_ID,
                        RESERVATION_ID
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verifyNoInteractions(attractionSlotService);
    }

    @Test
    @DisplayName("정원 복구에 실패하면 예외를 전달한다")
    void cancelReservation_capacityRestoreFailed() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getId()).thenReturn(SLOT_ID);
        when(slot.getStartsAt()).thenReturn(STARTS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findReservationByIdAndUser_Id(
                RESERVATION_ID,
                USER_ID
        )).thenReturn(Optional.of(reservation));

        doThrow(new BusinessException(
                ErrorCode.RESERVATION_CAPACITY_RESTORE_FAILED
        )).when(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);

        // when & then
        assertThatThrownBy(() ->
                cancelAt(DEADLINE.minusMinutes(1))
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_CAPACITY_RESTORE_FAILED
                        )
        );

        verify(attractionSlotService)
                .releaseReservationCapacity(SLOT_ID);
    }

    private Reservation createReservation(AttractionSlot slot) {
        Reservation reservation = new Reservation(
                mock(User.class),
                mock(Ticket.class),
                slot
        );

        ReflectionTestUtils.setField(
                reservation,
                "id",
                RESERVATION_ID
        );

        return reservation;
    }

    private UpdateReservationCancelResponse cancelAt(
            LocalDateTime currentTime
    ) {
        try (MockedStatic<KstTime> mocked = mockStatic(KstTime.class)) {
            mocked.when(KstTime::now).thenReturn(currentTime);

            return reservationService.cancelReservation(
                    USER_ID,
                    RESERVATION_ID
            );
        }
    }
}
