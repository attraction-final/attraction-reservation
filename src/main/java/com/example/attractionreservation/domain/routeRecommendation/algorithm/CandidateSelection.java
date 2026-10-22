package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;

//후보 선정 결과
public record CandidateSelection(
        List<CandidateAttraction> candidates,
        List<Exclusion> exclusions
) {
}
