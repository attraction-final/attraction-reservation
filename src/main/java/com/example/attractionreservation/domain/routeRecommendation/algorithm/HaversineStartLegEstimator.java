package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


 // 하버사인 직선거리로 첫 구간의 도보 거리·시간을 추정한다.

   //추정 도보 거리(m) = ceil(직선거리 × 우회 계수)
    //추정 이동 시간(분) = ceil(추정 도보 거리 / 보행 속도)

 //올림(ceil)을 쓰는 이유: 이동 시간을 짧게 잡으면 사용자가 늦게 도착한다. 짧게 틀리는 것보다 길게 틀리는 편이 안전하다.
 // 우회 계수·보행 속도 기본값은 attraction_paths 실측값에서 구했다. (RouteRecommendationProperties 참고)
@Component
@RequiredArgsConstructor
public class HaversineStartLegEstimator implements StartLegEstimator {

    private final RouteRecommendationProperties properties;

    @Override
    public StartLeg estimate(GeoPoint userLocation, GeoPoint attractionLocation) {
        double straightMeters = Haversine.distanceMeters(userLocation, attractionLocation);
        //도보거리 ->직선을 실제 걷는 거리로 보정
        int estimatedWalkMeters = (int) Math.ceil(straightMeters * properties.getDetourFactor());
        //거리를 시간으로 변환 -> /52.8
        int estimatedMinutes = (int) Math.ceil(estimatedWalkMeters / properties.getWalkingSpeedMetersPerMinute());
        //부동소수점 +올림 -> 결과가 정확히 정수여야 하는 경우 ceil이 1더 큰 값을 낼 수 있다.
        //이중 올림-> 거리에서 한 번, 시간에서 또 한 번 올리니까 최대 +1m, +1분의 보수적 오차가 쌓인다
        return new StartLeg(
                (int) Math.round(straightMeters),
                estimatedWalkMeters,
                estimatedMinutes
        );
    }
}
