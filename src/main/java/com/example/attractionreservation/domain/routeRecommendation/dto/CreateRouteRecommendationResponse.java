package com.example.attractionreservation.domain.routeRecommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

//현재 위치 기반 경로 추천 생성 결과.
//recommendation: 추천 결과 (FULL·PARTIAL 은 저장된 결과, NONE 은 저장하지 않은 결과)
//location: 요청 위치를 그대로 돌려주고 경고를 덧붙인 값. 위치는 저장하지 않는다.

public record CreateRouteRecommendationResponse(
        SearchRouteRecommendationResponse recommendation,
        Location location
) {

    public static CreateRouteRecommendationResponse of(SearchRouteRecommendationResponse recommendation,
                                                       Location location) {
        return new CreateRouteRecommendationResponse(recommendation, location);
    }

    // 요청 위치를 그대로 돌려주고 경고를 덧붙인다.
    public record Location(
            double latitude,
            double longitude,
            Double accuracy,
            Double altitude,
            Double altitudeAccuracy,
            Double heading,
            Double speed,
            LocalDateTime measuredAt, // timestamp(ms)를 KST 로 바꾼 값. 요청에 없으면 null
            List<LocationWarning> warnings
    ) {
    }

    // STALE_POSITION: 위치 측정 시각이 설정값(기본 5분)보다 오래됨
    public enum LocationWarning { STALE_POSITION }
}