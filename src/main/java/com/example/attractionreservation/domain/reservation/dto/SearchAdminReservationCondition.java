package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;

public record SearchAdminReservationCondition(
        @Min(1)
        Long userId,
        @Min(1)
        Long attractionId,
        ReservationStatus status,
        LocalDate date
) {
}
