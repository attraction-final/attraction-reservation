package com.example.attractionreservation.domain.routeRecommendation.entity.enums;

//추천 계산에 사용한 이동 시간의 기준
//DB 컬럼(route_recommendations.travel_time_basis)이 MySQL enum이므로 값을 추가할 때는 마이그레이션도 함께 추가한다.
public enum TravelTimeBasis {
    //모든 이동에 각 어트랙션의 정문 기준 이동 시간을 사용 (KAN-139 이전에 저장된 추천)
    ENTRANCE_FIXED,
    //첫 이동은 정문 기준, 이후 이동은 직전 어트랙션 → 다음 어트랙션 실측 시간(attraction_paths)을 사용
    //구간이 없거나 비활성이면 그 이동만 정문 기준 시간으로 대체
    MEASURED_PATH
}
