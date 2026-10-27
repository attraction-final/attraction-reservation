package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;

public record UpdateAdminReservationCancelResponse(
        Long reservationId,
        ReservationStatus status,
        Instant cancelledAt,
        ReservationCancelReason cancelReason,
        String cancelReasonMessage
) {
    public static UpdateAdminReservationCancelResponse from(
            Reservation reservation
    ) {
        ReservationCancelReason reason = reservation.getCancelReason();

        return new UpdateAdminReservationCancelResponse(
                reservation.getId(),
                reservation.getStatus(),
                reservation.getCancelledAt(),
                reason,
                reason == null ? null : reason.getMessage()
        );
    }
}