package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
//추천 계산 과정에서 제외된 어트랙션과 그 이유를 담음
public record Exclusion(
        Long attractionId,
        String attractionName,
        ExclusionReason reason
) {
}
