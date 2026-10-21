package com.example.attractionreservation.domain.attractionSlot.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.SlotStatus;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 관리자 날짜별 슬롯 조회 응답
public record SearchAdminAttractionSlotResponse(
        Long attractionId,
        String attractionName,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,
        List<SlotItem> slots
) {

    public static SearchAdminAttractionSlotResponse of(Attraction attraction, LocalDate date,
                                                       List<AttractionSlot> slots) {
        List<SlotItem> slotItems = slots.stream()
                .map(SlotItem::from)
                .toList();

        return new SearchAdminAttractionSlotResponse(
                attraction.getId(),
                attraction.getName(),
                date,
                slotItems
        );
    }

    public record SlotItem(
            Long slotId,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime startsAt,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime endsAt,
            Integer capacity,
            Integer reservedCount,
            Integer remainingCount,
            SlotStatus status
    ) {

        public static SlotItem from(AttractionSlot slot) {
            return new SlotItem(
                    slot.getId(),
                    slot.getStartsAt(),
                    slot.getEndsAt(),
                    slot.getCapacity(),
                    slot.getReservedCount(),
                    slot.getRemainingCount(),
                    slot.getStatus()
            );
        }
    }
}
