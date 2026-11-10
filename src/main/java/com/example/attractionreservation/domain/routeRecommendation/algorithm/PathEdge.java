package com.example.attractionreservation.domain.routeRecommendation.algorithm;

//계산용 어트랙션 간 이동 구간 (attraction_paths 의 활성 구간 한 행).
//엔티티 대신 이 record 를 쓰는 이유: 알고리즘이 JPA 에 의존하지 않게 해 순수 단위 테스트가 가능하도록.

public record PathEdge(
        Long fromAttractionId,
        Long toAttractionId,
        int distanceM,
        int moveMinutes,
        boolean estimated
) {
}
