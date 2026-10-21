package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.Enum.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.ReservableSlotView;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record SearchAttractionListResponse(
        List<AttractionItem> content,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    public static SearchAttractionListResponse of(Page<Attraction> attractionPage,
                                                  Map<Long, ReservableSlotView> nextSlotMap) {
        List<AttractionItem> content = attractionPage.getContent().stream()
                .map(attraction -> AttractionItem.of(attraction, nextSlotMap.get(attraction.getId())))
                .toList();

        return new SearchAttractionListResponse(
                content,
                attractionPage.getNumber(),
                attractionPage.getSize(),
                attractionPage.getTotalElements(),
                attractionPage.getTotalPages()
        );
    }

    public record AttractionItem(
            Long attractionId,
            String name,
            String description,
            String location,
            Integer durationMinutes,
            OperationStatus operationStatus,
            NextSlot nextSlot
    ) {

        public static AttractionItem of(Attraction attraction, ReservableSlotView slotView) {
            NextSlot nextSlot = slotView == null ? null : NextSlot.from(slotView);
            return new AttractionItem(
                    attraction.getId(),
                    attraction.getName(),
                    attraction.getDescription(),
                    attraction.getLocation(),
                    attraction.getDurationMinutes(),
                    attraction.getOperationStatus(),
                    nextSlot
            );
        }
    }

    public record NextSlot(
            Long slotId,
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
            LocalDateTime startsAt,
            Integer remainingCount
    ) {

        public static NextSlot from(ReservableSlotView slotView) {
            return new NextSlot(
                    slotView.getSlotId(),
                    slotView.getStartsAt(),
                    slotView.getRemainingCount()
            );
        }
    }
}
