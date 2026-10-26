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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReservationTest {

    @Test
    @DisplayName("회원이 없으면 예약을 생성할 수 없다")
    void createReservation_NullUser_ThrowsException() {
        // given
        Ticket ticket = mock(Ticket.class);
        AttractionSlot slot = mock(AttractionSlot.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(null, ticket, slot))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_USER));
    }

    @Test
    @DisplayName("티켓이 없으면 예약을 생성할 수 없다")
    void createReservation_NullTicket_ThrowsException() {
        // given
        User user = mock(User.class);
        AttractionSlot slot = mock(AttractionSlot.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(user, null, slot))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_TICKET));
    }

    @Test
    @DisplayName("슬롯이 없으면 예약을 생성할 수 없다")
    void createReservation_NullSlot_ThrowsException() {
        // given
        User user = mock(User.class);
        Ticket ticket = mock(Ticket.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(user, ticket, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_SLOT));
    }

    @Test
    @DisplayName("예약 완료 상태의 예약을 취소하면 상태와 취소 시각을 기록한다")
    void cancelReservation_Reserved_RecordsCancellation() {
        // given
        Reservation reservation = new Reservation(
                mock(User.class), mock(Ticket.class), mock(AttractionSlot.class)
        );
        Instant cancelledAt = Instant.parse("2026-10-25T04:30:00Z");

        // when
        reservation.cancelReservation(cancelledAt);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reservation.getCancelledAt()).isEqualTo(cancelledAt);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = "RESERVED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("예약 완료 상태가 아니면 Entity에서 취소를 거절하고 기존 값을 유지한다")
    void cancelReservation_InvalidStatus_PreservesExistingValues(ReservationStatus status) {
        // given
        Reservation reservation = new Reservation(
                mock(User.class), mock(Ticket.class), mock(AttractionSlot.class)
        );
        Instant previousCancelledAt = status == ReservationStatus.CANCELLED
                ? Instant.parse("2026-10-25T04:00:00Z") : null;
        ReflectionTestUtils.setField(reservation, "status", status);
        ReflectionTestUtils.setField(reservation, "cancelledAt", previousCancelledAt);
        Instant requestedAt = Instant.parse("2026-10-25T04:30:00Z");

        // when & then
        assertThatThrownBy(() -> reservation.cancelReservation(requestedAt))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS));
        assertThat(reservation.getStatus()).isEqualTo(status);
        assertThat(reservation.getCancelledAt()).isEqualTo(previousCancelledAt);
    }
}
