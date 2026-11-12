package com.example.attractionreservation.domain.attractionSlot.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateAttractionSlotStatusRequest(

        @NotBlank
        String status // OPEN, CLOSED, SUSPENDED 중 하나 (검증은 SlotStatus.from에서)
) {
}
