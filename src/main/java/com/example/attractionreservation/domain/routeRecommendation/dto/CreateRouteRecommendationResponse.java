package com.example.attractionreservation.domain.routeRecommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 현재 위치 기반 방문 순서 추천 결과.
 * 계산 결과만 담고 DB 에 저장하지 않는다. 조립은 Service 가 한다.
 */
public record CreateRouteRecommendationResponse(
        Location location,                        // 요청 위치 + 경고
        int totalDistanceM,                       // 전체 이동 거리(m) = legs 거리 합계
        int totalMoveMinutes,                     // 전체 이동 시간(분) = legs 시간 합계 (최적화 기준)
        boolean estimatedPathIncluded,            // 어트랙션 간 구간 중 is_estimated = true 인 구간을 사용했는지
                                                  // (첫 구간은 항상 추정이므로 legs[0].estimated 로 따로 표시)
        int evaluatedOrderCount,                  // 평가한 방문 순서 수 (5개면 120)
        int validOrderCount,                      // 그중 모든 구간이 이용 가능한 순서 수
        List<Visit> visits,                       // 추천 방문 순서
        List<Leg> legs,                           // 구간별 이동 정보 (legs[0] = 사용자 위치 → 첫 어트랙션)
        List<UnavailablePath> unavailablePaths    // 계산에서 제외된 구간 (비활성·데이터 없음)
) {

    /** 요청 위치를 그대로 돌려주고 경고를 덧붙인다. */
    public record Location(
            double latitude,
            double longitude,
            Double accuracy,
            Double altitude,
            Double altitudeAccuracy,
            Double heading,
            Double speed,
            LocalDateTime measuredAt,             // timestamp(ms)를 KST 로 바꾼 값. 요청에 없으면 null
            List<LocationWarning> warnings
    ) {
    }

    /** 방문지 1곳 */
    public record Visit(
            int sequence,
            Long attractionId,
            String attractionName,
            double latitude,
            double longitude
    ) {
    }

    /** 이동 구간 1개 */
    public record Leg(
            int sequence,
            LegFromType fromType,                 // USER_LOCATION 또는 ATTRACTION
            Long fromAttractionId,                // 첫 구간은 null
            Long toAttractionId,
            Integer straightDistanceM,            // 직선거리. 좌표로 계산한 첫 구간에만 있고 나머지는 null
            int distanceM,                        // 도보 거리 (첫 구간: 추정, 나머지: attraction_paths.distance_m)
            int moveMinutes,
            boolean estimated,                    // 첫 구간: 항상 true / 나머지: attraction_paths.is_estimated
            LegBasis basis                        // HAVERSINE_ESTIMATE 또는 ATTRACTION_PATH
    ) {

        public static Leg fromUserLocation(Long toAttractionId, int straightDistanceM, int distanceM, int moveMinutes) {
            return new Leg(1, LegFromType.USER_LOCATION, null, toAttractionId,
                    straightDistanceM, distanceM, moveMinutes, true, LegBasis.HAVERSINE_ESTIMATE);
        }

        public static Leg fromAttractionPath(int sequence, Long fromAttractionId, Long toAttractionId,
                                             int distanceM, int moveMinutes, boolean estimated) {
            return new Leg(sequence, LegFromType.ATTRACTION, fromAttractionId, toAttractionId,
                    null, distanceM, moveMinutes, estimated, LegBasis.ATTRACTION_PATH);
        }
    }

    /** 사용할 수 없어 계산에서 제외한 구간 */
    public record UnavailablePath(
            Long fromAttractionId,
            Long toAttractionId,
            UnavailableReason reason
    ) {
    }

    public enum LegFromType { USER_LOCATION, ATTRACTION }

    // HAVERSINE_ESTIMATE: 좌표 직선거리 × 우회 계수로 추정 / ATTRACTION_PATH: attraction_paths 데이터
    public enum LegBasis { HAVERSINE_ESTIMATE, ATTRACTION_PATH }

    // INACTIVE: is_active = false / MISSING: attraction_paths 에 행이 없음
    public enum UnavailableReason { INACTIVE, MISSING }

    // STALE_POSITION: 위치 측정 시각이 설정값(기본 5분)보다 오래됨
    public enum LocationWarning { STALE_POSITION }
}
