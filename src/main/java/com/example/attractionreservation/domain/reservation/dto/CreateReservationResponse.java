package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record CreateReservationResponse(
        Long reservationId,
        String reservationNumber,
        Long ticketId,
        Long slotId,
        String attractionName,
        ReservationStatus status,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Instant createdAt
) {
    public static CreateReservationResponse from(Reservation reservation) {
        return new CreateReservationResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getTicket().getId(),
                reservation.getAttractionSlot().getId(),
                reservation.getAttractionSlot().getAttraction().getName(),
                reservation.getStatus(),
                reservation.getAttractionSlot().getStartsAt(),
                reservation.getAttractionSlot().getEndsAt(),
                reservation.getCreatedAt()
        );
    }
}
