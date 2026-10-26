package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;

public record Exclusion(
        Long attractionId,
        String attractionName,
        ExclusionReason reason
) {
}
