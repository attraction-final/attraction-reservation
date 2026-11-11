package com.example.attractionreservation.domain.routeRecommendation.algorithm;

// WGS84 좌표 한 점 (위도, 경도: 도 단위) ->GPS가 쓰는 전 세계 표준 좌표계예요.
// 휴대폰이 보내는 위도·경도가 이 기준이라, 주석은 "이 숫자를 그 기준으로 해석해라"는 약속이에요.
public record GeoPoint(double latitude, double longitude) {
}
