package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

//좌표 두 개 사이의 도보 이동 시간을 추정한다.
//실측 구간이 없는 출발지(사용자 현재 위치)에서 첫 어트랙션까지의 이동 시간에 쓴다.
//이동 시간(분) = 하버사인 직선거리 × 우회 계수 ÷ 보행 속도, 올림
//우회 계수·보행 속도·공원 범위는 application.yaml(route-recommendation)에서 조정한다.
@Component
public class WalkingTimeCalculator {

    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final double detourFactor;
    private final double speedMetersPerMinute;
    private final double minLatitude;
    private final double maxLatitude;
    private final double minLongitude;
    private final double maxLongitude;

    public WalkingTimeCalculator(
            @Value("${route-recommendation.walking.detour-factor:1.7}") double detourFactor,
            @Value("${route-recommendation.walking.speed-meters-per-minute:53}") double speedMetersPerMinute,
            @Value("${route-recommendation.park-bounds.min-latitude:37.2870}") double minLatitude,
            @Value("${route-recommendation.park-bounds.max-latitude:37.2975}") double maxLatitude,
            @Value("${route-recommendation.park-bounds.min-longitude:127.1950}") double minLongitude,
            @Value("${route-recommendation.park-bounds.max-longitude:127.2095}") double maxLongitude
    ) {
        if (detourFactor < 1 || speedMetersPerMinute <= 0) {
            throw new IllegalArgumentException("우회 계수는 1 이상, 보행 속도는 0보다 커야 합니다.");
        }
        this.detourFactor = detourFactor;
        this.speedMetersPerMinute = speedMetersPerMinute;
        this.minLatitude = minLatitude;
        this.maxLatitude = maxLatitude;
        this.minLongitude = minLongitude;
        this.maxLongitude = maxLongitude;
    }

    //공원 범위(위·경도 사각형) 안의 좌표인지 확인한다. 경계값은 범위 안으로 본다.
    public boolean isInsidePark(Coordinate coordinate) {
        return coordinate.latitude() >= minLatitude && coordinate.latitude() <= maxLatitude
                && coordinate.longitude() >= minLongitude && coordinate.longitude() <= maxLongitude;
    }

    //from → to 도보 이동 시간(분). 실제보다 짧게 잡으면 슬롯에 늦으므로 올림한다.
    public int minutesBetween(Coordinate from, Coordinate to) {
        double walkingMeters = straightMeters(from, to) * detourFactor;
        return (int) Math.ceil(walkingMeters / speedMetersPerMinute);
    }

    //하버사인 공식으로 구한 두 좌표 사이의 직선거리(m)
    static double straightMeters(Coordinate from, Coordinate to) {
        double fromLatitude = Math.toRadians(from.latitude());
        double toLatitude = Math.toRadians(to.latitude());
        double latitudeDelta = toLatitude - fromLatitude;
        double longitudeDelta = Math.toRadians(to.longitude() - from.longitude());

        double haversine = Math.pow(Math.sin(latitudeDelta / 2), 2)
                + Math.cos(fromLatitude) * Math.cos(toLatitude) * Math.pow(Math.sin(longitudeDelta / 2), 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(haversine));
    }
}
