package com.example.attractionreservation.domain.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateAdminReservationCancelRequest(
        @NotNull(message = "취소 사유는 필수입니다.")
        String cancelReason
) {
}
