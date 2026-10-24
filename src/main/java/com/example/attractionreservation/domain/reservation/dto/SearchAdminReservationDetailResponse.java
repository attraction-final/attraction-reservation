package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record SearchAdminReservationDetailResponse(
        Long reservationId,
        String reservationNumber,
        Long userId,
        Long ticketId,
        Long attractionId,
        String attractionName,
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        ReservationStatus status,
        Instant createdAt,
        Instant cancelledAt,
        String cancelReason
) {
    public static SearchAdminReservationDetailResponse from(Reservation reservation) {
        return new SearchAdminReservationDetailResponse(
                reservation.getId(),
                reservation.getReservationNumber(),
                reservation.getUser().getId(),
                reservation.getTicket().getId(),
                reservation.getAttractionSlot().getAttraction().getId(),
                reservation.getAttractionSlot().getAttraction().getName(),
                reservation.getAttractionSlot().getId(),
                reservation.getAttractionSlot().getStartsAt(),
                reservation.getAttractionSlot().getEndsAt(),
                reservation.getStatus(),
                reservation.getCreatedAt(),
                reservation.getCancelledAt(),
                reservation.getCancelReason()
        );
    }
}
