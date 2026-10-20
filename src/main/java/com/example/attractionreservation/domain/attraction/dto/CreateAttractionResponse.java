package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.OperationStatus;
import com.example.attractionreservation.global.util.KstTime;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record CreateAttractionResponse(
        Long attractionId,
        String name,
        String description,
        String location,
        Integer durationMinutes, // 이용 소요 시간(분)
        OperationStatus operationStatus,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") // 날짜와 시간을 JSON으로 변환할 때 사용할 형식을 지정
        LocalDateTime createdAt
) {

    public static CreateAttractionResponse from(Attraction attraction) {
        return new CreateAttractionResponse(
                attraction.getId(),
                attraction.getName(),
                attraction.getDescription(),
                attraction.getLocation(),
                attraction.getDurationMinutes(), // 이용 소요 시간(분)
                attraction.getOperationStatus(),
                KstTime.toLocalDateTime(attraction.getCreatedAt()) // 엔티티의 생성 시각을 KstTime을 통해 LocalDateTime으로 변환
        );
    }
}
