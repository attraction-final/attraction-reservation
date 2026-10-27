package com.example.attractionreservation.domain.attractionSlot.dto;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record UpdateAttractionSlotTimeResponse(
        Long slotId,
        Long attractionId,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime startsAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime endsAt,
        Integer capacity,
        Integer reservedCount,
        Integer remainingCount,
        SlotStatus status
) {

    public static UpdateAttractionSlotTimeResponse from(AttractionSlot slot) {
        return new UpdateAttractionSlotTimeResponse(
                slot.getId(),
                slot.getAttraction().getId(),
                slot.getStartsAt(),
                slot.getEndsAt(),
                slot.getCapacity(),
                slot.getReservedCount(),
                slot.getRemainingCount(),
                slot.getStatus()
        );
    }
}
