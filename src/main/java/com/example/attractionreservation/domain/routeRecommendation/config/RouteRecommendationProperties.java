package com.example.attractionreservation.domain.routeRecommendation.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;


 //현재 위치 기반 경로 추천 설정 (application.yaml 의 route-recommendation.*)
 //잘못된 값이면 애플리케이션 시작 시점에 실패한다.

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "route-recommendation")
public class RouteRecommendationProperties {


    // 기본값 1.68 은 attraction_paths 실측 90건과 좌표로 계산한 중앙값이다. (사분위 1.34 ~ 1.98)
    @NotNull
    @DecimalMin("1.0") //직선거리보다 짧게 걸을 수는 없으므로 1.0 이상만 허용한다
    private Double detourFactor = 1.68; //우회 계수 = 실제 도보 거리 / 직선거리.
    //출발지와 목적지 사이 실제 이동 거리를 두 지점간의 직선거리
    // (유클리드 거리 또는 최단직선)로 나눈 비율

     // 평균 보행 속도(m/분).
     // 기본값 52.8 은 attraction_paths 실측 90건의 distance_m / move_minutes 중앙값이다. (사분위 46.9 ~ 59.2)
    @NotNull
    @Positive //값이 양수여야한다.
    private Double walkingSpeedMetersPerMinute = 52.8;

    //위치 정확도(오차 반경, m)가 이 값 이상이면 요청을 거부
    @NotNull
    @Positive //설정 클래스의 @Positive →  앱 시작할 때 1번 검사 → 앱이 아예 안 뜸
    private Double accuracyLimitMeters = 100.0;

    //선택한 어트랙션 중 가장 가까운 곳까지의 직선거리가 이 값을 넘으면 서비스 지역 밖으로 보고 거부한다.
    @NotNull
    @Positive
    private Integer maxStartDistanceMeters = 2000;
    //현재 최근접 어트랙션 반경 -> 경계가 원이라 정확성은 높지 않음 , 좋은점은 데이터가 필요없음
    //추후 고도화 아이디어 -> Geofence 정확하지만 ,경계 데이터를 만들어서 관리해야함

    //오래된 위치는 틀릴수 있지만 필요없진 않아서 -> 요청을 거부하지 않고 경고만 붙여서 판단을 클라이언트에 넘긴다.
    @NotNull
    private Duration staleAfter = Duration.ofMinutes(5);
}
