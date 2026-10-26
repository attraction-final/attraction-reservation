package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusRequest;
import com.example.attractionreservation.domain.reservation.dto.UpdateAdminReservationStatusResponse;
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
class AdminReservationStatusServiceTest {

    private static final Long RESERVATION_ID = 100L;

    private static final LocalDateTime ENDS_AT =
            LocalDateTime.of(2026, 10, 24, 16, 10);

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"COMPLETED", "NO_SHOW"}
    )
    @DisplayName("슬롯 종료 이후 상태를 변경하고 flush 후 응답을 생성한다")
    void updateReservationStatus_success(ReservationStatus targetStatus) {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getEndsAt()).thenReturn(ENDS_AT);

        Reservation reservation = createReservation(slot);
        Instant previousUpdatedAt =
                Instant.parse("2026-10-24T01:00:00Z");
        Instant updatedAt =
                Instant.parse("2026-10-24T07:11:00Z");

        ReflectionTestUtils.setField(
                reservation,
                "updatedAt",
                previousUpdatedAt
        );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        doAnswer(invocation -> {
            assertThat(reservation.getStatus()).isEqualTo(targetStatus);

            ReflectionTestUtils.setField(
                    reservation,
                    "updatedAt",
                    updatedAt
            );
            return null;
        }).when(reservationRepository).flush();

        // when
        UpdateAdminReservationStatusResponse response = updateAt(
                ENDS_AT.plusMinutes(1),
                targetStatus
        );

        // then
        assertThat(reservation.getStatus()).isEqualTo(targetStatus);
        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(response.reservationNumber())
                .isEqualTo(reservation.getReservationNumber());
        assertThat(response.status()).isEqualTo(targetStatus);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getCancelReason()).isNull();

        verify(reservationRepository).flush();
    }

    @Test
    @DisplayName("슬롯 종료 정각에는 상태를 변경할 수 있다")
    void updateReservationStatus_atEndTime() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getEndsAt()).thenReturn(ENDS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when
        UpdateAdminReservationStatusResponse response = updateAt(
                ENDS_AT,
                ReservationStatus.COMPLETED
        );

        // then
        assertThat(response.status())
                .isEqualTo(ReservationStatus.COMPLETED);
        verify(reservationRepository).flush();
    }

    @Test
    @DisplayName("슬롯 종료 전에는 상태 변경을 거절한다")
    void updateReservationStatus_beforeEndTime() {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getEndsAt()).thenReturn(ENDS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                updateAt(
                        ENDS_AT.minusNanos(1),
                        ReservationStatus.COMPLETED
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_SLOT_NOT_ENDED)
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        verify(reservationRepository, never()).flush();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = "RESERVED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("현재 예약 완료 상태가 아니면 변경하지 않고 flush도 호출하지 않는다")
    void updateReservationStatus_invalidCurrentStatus(
            ReservationStatus currentStatus
    ) {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getEndsAt()).thenReturn(ENDS_AT);

        Reservation reservation = createReservation(slot);
        ReflectionTestUtils.setField(
                reservation,
                "status",
                currentStatus
        );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                updateAt(
                        ENDS_AT.plusMinutes(1),
                        ReservationStatus.COMPLETED
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.RESERVATION_STATUS_CHANGE_NOT_ALLOWED
                        )
        );

        assertThat(reservation.getStatus()).isEqualTo(currentStatus);
        verify(reservationRepository, never()).flush();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"COMPLETED", "NO_SHOW"},
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("허용되지 않은 목표 상태이면 변경하지 않고 flush도 호출하지 않는다")
    void updateReservationStatus_invalidTargetStatus(
            ReservationStatus targetStatus
    ) {
        // given
        AttractionSlot slot = mock(AttractionSlot.class);
        when(slot.getEndsAt()).thenReturn(ENDS_AT);

        Reservation reservation = createReservation(slot);

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.of(reservation));

        // when & then
        assertThatThrownBy(() ->
                updateAt(ENDS_AT.plusMinutes(1), targetStatus)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.INVALID_RESERVATION_TARGET_STATUS
                        )
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        verify(reservationRepository, never()).flush();
    }

    @Test
    @DisplayName("예약이 없으면 조회 실패 예외를 반환한다")
    void updateReservationStatus_notFound() {
        // given
        UpdateAdminReservationStatusRequest request =
                new UpdateAdminReservationStatusRequest(
                        ReservationStatus.COMPLETED
                );

        when(reservationRepository.findById(RESERVATION_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                reservationService.updateReservationStatus(
                        RESERVATION_ID,
                        request
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND_RESERVATION)
        );

        verify(reservationRepository, never()).flush();
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

    private UpdateAdminReservationStatusResponse updateAt(
            LocalDateTime now,
            ReservationStatus targetStatus
    ) {
        try (MockedStatic<KstTime> mocked = mockStatic(KstTime.class)) {
            mocked.when(() ->
                    KstTime.toLocalDateTime(any(Instant.class))
            ).thenReturn(now);

            return reservationService.updateReservationStatus(
                    RESERVATION_ID,
                    new UpdateAdminReservationStatusRequest(targetStatus)
            );
        }
    }
}