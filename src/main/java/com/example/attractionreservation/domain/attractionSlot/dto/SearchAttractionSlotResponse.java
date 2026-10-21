package com.example.attractionreservation.domain.attractionSlot.dto;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 사용자 날짜별 슬롯 조회 응답
public record SearchAttractionSlotResponse(
        Long attractionId,
        String attractionName,
        OperationStatus operationStatus,
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate date,
        List<SlotItem> slots
) {

    public static SearchAttractionSlotResponse of(Attraction attraction, LocalDate date,
                                                  List<AttractionSlot> slots, LocalDateTime now) {
        boolean isAttractionOperating = attraction.isOperating();

        List<SlotItem> slotItems = slots.stream()
                .map(slot -> SlotItem.of(slot, slot.findUnavailableReason(isAttractionOperating, now)))
                .toList();

        return new SearchAttractionSlotResponse(
                attraction.getId(),
                attraction.getName(),
                attraction.getOperationStatus(),
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
            Integer remainingCount,
            SlotStatus status,
            Boolean reservable,
            UnavailableReason unavailableReason // 예약 가능하면 null
    ) {

        public static SlotItem of(AttractionSlot slot, UnavailableReason unavailableReason) {
            return new SlotItem(
                    slot.getId(),
                    slot.getStartsAt(),
                    slot.getEndsAt(),
                    slot.getCapacity(),
                    slot.getRemainingCount(),
                    slot.getStatus(),
                    unavailableReason == null, // 이유가 없으면 예약 가능
                    unavailableReason
            );
        }
    }
}
