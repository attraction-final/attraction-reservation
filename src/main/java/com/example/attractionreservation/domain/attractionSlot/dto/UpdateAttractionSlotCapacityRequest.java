package com.example.attractionreservation.domain.attractionSlot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateAttractionSlotCapacityRequest(

        @NotNull
        @Min(1)
        Integer capacity
) {
}
