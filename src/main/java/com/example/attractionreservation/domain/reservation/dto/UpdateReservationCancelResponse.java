package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;

public record UpdateReservationCancelResponse(
        Long reservationId,
        String reservationNumber,
        ReservationStatus status,
        Instant cancelledAt
) {
    public static UpdateReservationCancelResponse from(Reservation reservation) {
        return new UpdateReservationCancelResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getStatus(),
                reservation.getCancelledAt()
        );
    }
}
