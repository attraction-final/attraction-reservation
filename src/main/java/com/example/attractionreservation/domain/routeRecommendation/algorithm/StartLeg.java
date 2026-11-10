package com.example.attractionreservation.domain.routeRecommendation.algorithm;


 // 사용자 현재 위치 → 어트랙션 첫 구간의 추정 이동 정보.
 // 직선거리와 추정 도보 거리를 따로 담아 둘을 혼동하지 않게 한다.

public record StartLeg(
        int straightDistanceM,   // 하버사인 직선거리
        int distanceM,           // 추정 도보 거리 = 직선거리 × 우회 계수
        int moveMinutes          // 추정 이동 시간 = 추정 도보 거리 / 보행 속도
) {
}
