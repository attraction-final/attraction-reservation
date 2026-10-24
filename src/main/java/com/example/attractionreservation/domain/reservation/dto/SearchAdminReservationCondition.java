package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.LocalDate;

public record SearchAdminReservationCondition(
        Long userId,
        Long attractionId,
        ReservationStatus status,
        LocalDate date
) {
}
