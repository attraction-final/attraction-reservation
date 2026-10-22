package com.example.attractionreservation.domain.attractionSlot.dto;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;

public record UpdateAttractionSlotCapacityResponse(
        Long slotId,
        Integer capacity,
        Integer reservedCount,
        Integer remainingCount,
        SlotStatus status
) {

    public static UpdateAttractionSlotCapacityResponse from(AttractionSlot slot) {
        return new UpdateAttractionSlotCapacityResponse(
                slot.getId(),
                slot.getCapacity(),
                slot.getReservedCount(),
                slot.getRemainingCount(),
                slot.getStatus()
        );
    }
}
