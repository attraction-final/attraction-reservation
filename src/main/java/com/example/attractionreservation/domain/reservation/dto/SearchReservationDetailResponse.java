package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record SearchReservationDetailResponse(
        Long reservationId,
        String reservationNumber,
        Long attractionId,
        String attractionName,
        Long ticketId,
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        ReservationStatus status,
        Instant createdAt,
        String cancelReason,
        Instant cancelledAt
) {
    public static SearchReservationDetailResponse from(Reservation reservation) {
        return new SearchReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getAttractionSlot().getAttraction().getId(),
                reservation.getAttractionSlot().getAttraction().getName(),
                reservation.getTicket().getId(),
                reservation.getAttractionSlot().getId(),
                reservation.getAttractionSlot().getStartsAt(),
                reservation.getAttractionSlot().getEndsAt(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getCancelReason(),
                reservation.getCancelledAt()
        );
    }
}
