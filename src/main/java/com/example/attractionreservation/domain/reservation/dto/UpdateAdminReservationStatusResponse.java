package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;

public record UpdateAdminReservationStatusResponse(
        Long reservationId,
        String reservationNumber,
        ReservationStatus status,
        Instant updatedAt
) {
    public static UpdateAdminReservationStatusResponse from(Reservation reservation) {
        return new UpdateAdminReservationStatusResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getStatus(),
                reservation.getUpdatedAt()
        );
    }
}
