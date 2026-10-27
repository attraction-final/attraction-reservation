package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//추천 계산에 필요한 어트랙션 정보만 담은 스냅샷
public record AttractionView(
        Long attractionId,
        String attractionName,
        boolean operating,
        int moveMinutes  //정문 -> 어트랙션 기준 이동 시간 travelTimeBasis = ENTRANCE_FIXED
) {
}
