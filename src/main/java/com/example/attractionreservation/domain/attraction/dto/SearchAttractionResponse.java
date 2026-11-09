package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.global.util.KstTime;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

// 어트랙션 상세 조회
// ★ Serializable 추가: Redis에 저장하려면 객체를 바이트로 바꿀 수 있어야 한다
//   (RedisCacheManager 기본 저장 방식이 Java 직렬화)
public record SearchAttractionResponse(
        Long attractionId,
        String name,
        String description,
        String location,
        Integer durationMinutes,
        Integer entranceMoveMinutes,
        OperationStatus operationStatus,
        // 탑승 키 정보 (KAN-117). 모두 NULL 가능: NULL은 "제한 없음"이 아니라 "등록된 정보 없음"
        Integer minHeightCm,       // 혼자 탈 수 있는 최소 키
        Integer maxHeightCm,       // 탈 수 있는 최대 키
        Integer guardianHeightCm,  // 이 키 이하는 보호자와 함께 타야 함
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime updatedAt
) implements Serializable {

    public static SearchAttractionResponse from(Attraction attraction) {
        return new SearchAttractionResponse(
                attraction.getId(),
                attraction.getName(),
                attraction.getDescription(),
                attraction.getLocation(),
                attraction.getDurationMinutes(),
                attraction.getEntranceMoveMinutes(),
                attraction.getOperationStatus(),
                attraction.getMinHeightCm(),
                attraction.getMaxHeightCm(),
                attraction.getGuardianHeightCm(),
                KstTime.toLocalDateTime(attraction.getCreatedAt()),
                KstTime.toLocalDateTime(attraction.getUpdatedAt())
        );
    }
}