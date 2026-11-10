package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;

/**
 * 완전 탐색 결과.
 * 유효한 순서가 하나도 없으면 order 가 비어 있다. (hasValidOrder() == false)
 */
public record VisitOrderResult(
        List<Long> order,              // 최적 방문 순서 (어트랙션 ID)
        StartLeg startLeg,             // 사용자 위치 → order[0] 구간
        List<PathEdge> edges,          // order[i] → order[i+1] 구간 (order.size() - 1 개)
        int totalMoveMinutes,
        int totalDistanceM,
        int evaluatedOrderCount,       // 평가한 순서 수 (n!)
        int validOrderCount            // 모든 구간이 이용 가능한 순서 수
) {

    public static VisitOrderResult noValidOrder(int evaluatedOrderCount) {
        return new VisitOrderResult(List.of(), null, List.of(), 0, 0, evaluatedOrderCount, 0);
    }

    public boolean hasValidOrder() {
        return !order.isEmpty();
    }
}
