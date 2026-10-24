package com.example.attractionreservation.domain.reservation.entity;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReservationStatusUpdateTest {

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"COMPLETED", "NO_SHOW"}
    )
    @DisplayName("예약 완료 상태를 이용 완료 또는 노쇼로 변경한다")
    void updateReservationStatus_success(ReservationStatus targetStatus) {
        // given
        Reservation reservation = createReservation();

        // when
        reservation.updateReservationStatus(targetStatus);

        // then
        assertThat(reservation.getStatus()).isEqualTo(targetStatus);
        assertThat(reservation.getCancelledAt()).isNull();
        assertThat(reservation.getCancelReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = "RESERVED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("현재 예약 완료 상태가 아니면 상태 변경을 거절한다")
    void updateReservationStatus_invalidCurrentStatus(
            ReservationStatus currentStatus
    ) {
        // given
        Reservation reservation = createReservation();
        ReflectionTestUtils.setField(
                reservation,
                "status",
                currentStatus
        );

        // when & then
        assertThatThrownBy(() ->
                reservation.updateReservationStatus(
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
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"COMPLETED", "NO_SHOW"},
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("이용 완료와 노쇼 이외의 목표 상태는 거절한다")
    void updateReservationStatus_invalidTargetStatus(
            ReservationStatus targetStatus
    ) {
        // given
        Reservation reservation = createReservation();

        // when & then
        assertThatThrownBy(() ->
                reservation.updateReservationStatus(targetStatus)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.INVALID_RESERVATION_TARGET_STATUS
                        )
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("목표 상태가 null이면 상태 변경을 거절한다")
    void updateReservationStatus_nullTargetStatus() {
        // given
        Reservation reservation = createReservation();

        // when & then
        assertThatThrownBy(() ->
                reservation.updateReservationStatus(null)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ErrorCode.INVALID_RESERVATION_TARGET_STATUS
                        )
        );

        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
    }

    private Reservation createReservation() {
        return new Reservation(
                mock(User.class),
                mock(Ticket.class),
                mock(AttractionSlot.class)
        );
    }
}