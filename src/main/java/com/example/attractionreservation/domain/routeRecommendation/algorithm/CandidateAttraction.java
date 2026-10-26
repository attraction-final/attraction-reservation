package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;

public record CandidateAttraction(
        Long attractionId,
        String attractionName,
        int moveMinutes,
        List<CandidateSlot> slots
) {
}
