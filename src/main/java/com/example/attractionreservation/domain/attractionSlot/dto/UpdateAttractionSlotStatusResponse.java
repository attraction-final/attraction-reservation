package com.example.attractionreservation.domain.attractionSlot.dto;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;

public record UpdateAttractionSlotStatusResponse(
        Long slotId,
        SlotStatus previousStatus,
        SlotStatus status,
        Integer reservedCount
) {

    public static UpdateAttractionSlotStatusResponse of(AttractionSlot slot, SlotStatus previousStatus) {
        return new UpdateAttractionSlotStatusResponse(
                slot.getId(),
                previousStatus,
                slot.getStatus(),
                slot.getReservedCount()
        );
    }
}
