package com.example.attractionreservation.domain.reservation.dto;

import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAdminReservationStatusRequest(
        @NotNull(message = "변경할 예약 상태를 입력해주세요.")
        ReservationStatus status
) {
}
