package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record SearchAdminReservationResponse(
        Long reservationId,
        String reservationNumber,
        Long userId,
        Long attractionId,
        String attractionName,
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        ReservationStatus status,
        Instant createdAt
) {
    public static SearchAdminReservationResponse from(Reservation reservation) {
        return new SearchAdminReservationResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getUser().getId(),
                reservation.getAttractionSlot().getAttraction().getId(),
                reservation.getAttractionSlot().getAttraction().getName(),
                reservation.getAttractionSlot().getId(),
                reservation.getAttractionSlot().getStartsAt(),
                reservation.getAttractionSlot().getEndsAt(),
                reservation.getStatus(),
                reservation.getCreatedAt()
        );
    }
}
