package com.example.attractionreservation.domain.routeRecommendation.algorithm;

//사용자 위치 → 어트랙션 첫 구간의 이동 정보를 계산한다.
// 지금은 하버사인 추정(HaversineStartLegEstimator)만 있다.
//실제 도보 길찾기 API를 쓰게 되면 이 인터페이스의 구현체만 추가하면 된다.
// -> 지금 판단으로는 하버사인으로 구해도 오차가 별로 안나서 추천 결과에 영향을 안줄거같음
//추후 교체 계획이 있을 수도 있으니 인터페이스 남겨 두기

public interface StartLegEstimator {

    StartLeg estimate(GeoPoint userLocation, GeoPoint attractionLocation);
}
