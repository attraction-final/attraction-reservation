package com.example.attractionreservation.domain.reservation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateReservationRequest(

        @NotNull
        @Min(1)
        Long ticketId,

        @NotNull
        @Min(1)
        Long slotId
) {
}
