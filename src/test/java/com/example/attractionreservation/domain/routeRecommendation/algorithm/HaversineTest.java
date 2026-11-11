package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.RouteFixture;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HaversineTest {

    @Test
    @DisplayName("같은 좌표 사이의 거리는 0이다")
    void samePoint_isZero() {
        GeoPoint point = new GeoPoint(37.2930, 127.2010);

        assertThat(Haversine.distanceMeters(point, point)).isZero();
    }

    @Test
    @DisplayName("경도가 같고 위도가 1도 차이 나면 지구 둘레/360 ≈ 111,194.93m 다")
    void oneDegreeLatitude_isEarthCircumferenceOver360() {
        // 2πR / 360 = 2 × π × 6,371,000 / 360
        double expected = 2 * Math.PI * 6_371_000 / 360;

        double actual = Haversine.distanceMeters(new GeoPoint(37.0, 127.0), new GeoPoint(38.0, 127.0));

        assertThat(actual).isCloseTo(expected, within(0.01));
    }

    @Test
    @DisplayName("직선거리는 방향과 무관하다 (A→B = B→A)")
    void isSymmetric() {
        GeoPoint a = RouteFixture.LOCATIONS.get(RouteFixture.BUMPER_CAR);
        GeoPoint b = RouteFixture.LOCATIONS.get(RouteFixture.AMAZON);

        assertThat(Haversine.distanceMeters(a, b)).isEqualTo(Haversine.distanceMeters(b, a));
    }

    @Test
    @DisplayName("첫 구간 추정: 직선거리 × 우회계수 1.68 을 올림하고, 52.8m/분으로 나눠 분을 올림한다")
    void startLegEstimate_appliesDetourFactorAndWalkingSpeed() {
        // 기대값은 같은 공식을 Python 으로 따로 계산한 값이다.
        // 사용자 (37.2930, 127.2010) → 범퍼카: 직선 145m → 도보 ceil(145.x × 1.68) = 244m → ceil(244 / 52.8) = 5분
        HaversineStartLegEstimator estimator = new HaversineStartLegEstimator(new RouteRecommendationProperties());

        StartLeg leg = estimator.estimate(RouteFixture.USER_CENTER, RouteFixture.LOCATIONS.get(RouteFixture.BUMPER_CAR));

        assertThat(leg).isEqualTo(new StartLeg(145, 244, 5));
        // 직선거리와 추정 도보거리를 혼동하지 않는다: 추정 도보 거리는 직선거리보다 항상 길거나 같다
        assertThat(leg.distanceM()).isGreaterThanOrEqualTo(leg.straightDistanceM());
    }

    @Test
    @DisplayName("사용자가 어트랙션 좌표 위에 있으면 첫 구간은 0m, 0분이다")
    void startLegEstimate_atSamePoint_isZero() {
        HaversineStartLegEstimator estimator = new HaversineStartLegEstimator(new RouteRecommendationProperties());
        GeoPoint bumperCar = RouteFixture.LOCATIONS.get(RouteFixture.BUMPER_CAR);

        assertThat(estimator.estimate(bumperCar, bumperCar)).isEqualTo(new StartLeg(0, 0, 0));
    }
}
