package com.example.attractionreservation.domain.attraction.dto;

import com.example.attractionreservation.domain.attraction.entity.Attraction;

// 되묻기(NEED_CLARIFICATION) 때 사용자에게 보여줄 어트랙션 선택지
public record AttractionCandidate(
        Long attractionId,
        String name
) {
    public static AttractionCandidate from(Attraction attraction) {
        return new AttractionCandidate(attraction.getId(), attraction.getName());
    }
}