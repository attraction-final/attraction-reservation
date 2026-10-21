package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.global.util.KstTime;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

// 어트랙션 상세 조회
public record SearchAttractionResponse(
        Long attractionId,
        String name,
        String description,
        String location,
        Integer durationMinutes,
        Integer entranceMoveMinutes,
        OperationStatus operationStatus,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) {

    public static SearchAttractionResponse from(Attraction attraction) {
        return new SearchAttractionResponse(
                attraction.getId(),
                attraction.getName(),
                attraction.getDescription(),
                attraction.getLocation(),
                attraction.getDurationMinutes(),
                attraction.getEntranceMoveMinutes(),
                attraction.getOperationStatus(),
                KstTime.toLocalDateTime(attraction.getCreatedAt()),
                KstTime.toLocalDateTime(attraction.getUpdatedAt())
        );
    }
}
