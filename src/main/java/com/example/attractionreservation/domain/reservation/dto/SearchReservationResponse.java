package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record SearchReservationResponse(
        Long reservationId,
        String reservationNumber,
        Long slotId,
        String attractionName,
        Long ticketId,
        ReservationStatus status,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Instant createdAt
) {
    public static SearchReservationResponse from(Reservation reservation) {
        return new SearchReservationResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getAttractionSlot().getId(),
                reservation.getAttractionSlot().getAttraction().getName(),
                reservation.getTicket().getId(),
                reservation.getStatus(),
                reservation.getAttractionSlot().getStartsAt(),
                reservation.getAttractionSlot().getEndsAt(),
                reservation.getCreatedAt()
        );
    }
}
