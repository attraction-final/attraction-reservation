package com.example.attractionreservation.domain.routeRecommendation.entity.enums;

//추천 경로의 출발 위치
//DB 컬럼(route_recommendations.start_location)이 MySQL enum이므로 값을 추가할 때는 마이그레이션도 함께 추가한다.
public enum StartLocation {
    //정문에서 출발. 위치 없이 요청하면 이 값이다
    ENTRANCE,
    //사용자 현재 위치에서 출발 (KAN-140). 좌표 원본은 저장하지 않는다
    CURRENT_LOCATION
}
