package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;

import java.time.LocalDate;


public record SearchReservationCondition(
        ReservationStatus status,
        LocalDate date
) {
}
