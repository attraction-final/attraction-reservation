package com.example.attractionreservation.domain.routeRecommendation.algorithm;

/**
 * 하버사인(Haversine) 공식: 지구를 구로 보고 두 좌표 사이의 "직선(대원) 거리"를 구한다.
 *
 * 주의: 이 값은 건물·길을 무시한 직선거리다. 실제 도보 거리가 아니다.
 * 도보 거리로 쓰려면 우회 계수를 곱해야 한다. (HaversineStartLegEstimator 참고)
 *
 * 공식:
 *   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
 *   d = 2R · asin(√a)
 *   (φ: 위도, λ: 경도, 라디안 단위)
 */
public final class Haversine {

    // 지구 평균 반지름을 미터 단위로 저장한 상수
    static final double EARTH_RADIUS_METERS = 6_371_000;

    private Haversine() {
    }

    public static double distanceMeters(GeoPoint from, GeoPoint to) {
        double fromLatitude = Math.toRadians(from.latitude());
        double toLatitude = Math.toRadians(to.latitude());
        double deltaLatitude = toLatitude - fromLatitude;
        double deltaLongitude = Math.toRadians(to.longitude() - from.longitude());

        double a = Math.pow(Math.sin(deltaLatitude / 2), 2)
                + Math.cos(fromLatitude) * Math.cos(toLatitude) * Math.pow(Math.sin(deltaLongitude / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a));
    }
}
