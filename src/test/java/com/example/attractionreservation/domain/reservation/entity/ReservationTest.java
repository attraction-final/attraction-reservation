package com.example.attractionreservation.domain.reservation.entity;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReservationTest {

    @Test
    @DisplayName("예약 생성 시 연관관계와 예약 번호 및 초기 상태가 설정된다")
    void createReservation_success() {
        // given
        User user = mock(User.class);
        Ticket ticket = mock(Ticket.class);
        AttractionSlot slot = mock(AttractionSlot.class);

        // when
        Reservation reservation = new Reservation(user, ticket, slot);

        // then
        assertThat(reservation.getUser()).isSameAs(user);
        assertThat(reservation.getTicket()).isSameAs(ticket);
        assertThat(reservation.getAttractionSlot()).isSameAs(slot);
        assertThat(reservation.getStatus())
                .isEqualTo(ReservationStatus.RESERVED);

        assertThat(reservation.getReservationNumber())
                .startsWith("RSV-")
                .hasSize(20);

        assertThat(reservation.getCancelReason()).isNull();
        assertThat(reservation.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("회원 정보가 없으면 예약 생성에 실패한다")
    void createReservation_nullUser() {
        // given
        Ticket ticket = mock(Ticket.class);
        AttractionSlot slot = mock(AttractionSlot.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(null, ticket, slot))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_USER)
                );
    }

    @Test
    @DisplayName("티켓 정보가 없으면 예약 생성에 실패한다")
    void createReservation_nullTicket() {
        // given
        User user = mock(User.class);
        AttractionSlot slot = mock(AttractionSlot.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(user, null, slot))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_TICKET)
                );
    }

    @Test
    @DisplayName("슬롯 정보가 없으면 예약 생성에 실패한다")
    void createReservation_nullSlot() {
        // given
        User user = mock(User.class);
        Ticket ticket = mock(Ticket.class);

        // when & then
        assertThatThrownBy(() -> new Reservation(user, ticket, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_RESERVATION_SLOT)
                );
    }
}