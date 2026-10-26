package com.example.attractionreservation.domain.routeRecommendation.entity.enums;
//추천 결과에 포함된 어트랙션 범위 -> 사용자가 선택한 어트랙션 중 실제 추천 경로에 포함된 어트랙션
public enum RouteResultType {
    FULL, //선택한 어트랙션 전부 포함
    PARTIAL, //일부만 포함
    NONE //하나도 포함 못함
}
