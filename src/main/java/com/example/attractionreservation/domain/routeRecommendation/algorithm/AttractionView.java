package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//추천 계산에 필요한 어트랙션 정보만 담은 스냅샷
public record AttractionView(
        Long attractionId, //슬롯 조회, 중복 방문 방지, 최종 결과 연결에 사용
        String attractionName,
        boolean operating, //추천 계산에 사용하는 운영 여부
        int moveMinutes  //해당 어트랙션의 정문 기준 이동 시간
) {
}
