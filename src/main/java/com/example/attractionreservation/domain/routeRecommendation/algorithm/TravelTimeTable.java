package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

// 어트랙션 간 이동 구간 조회표 (attraction_paths 의 활성 구간)
// 추천 1건당 구간을 DB에서 한 번만 읽어 이 조회표로 만들고, 탐색 중에는 메모리에서만 조회한다.
// (5개 선택 시 방문 순서 120가지 × 구간마다 DB를 조회하면 N+1이 된다)
public final class TravelTimeTable {

    private static final TravelTimeTable EMPTY = new TravelTimeTable(Map.of());

    // 출발 어트랙션 ID → (도착 어트랙션 ID → 구간)
    private final Map<Long, Map<Long, PathEdge>> edgesByFromAndTo;

    private TravelTimeTable(Map<Long, Map<Long, PathEdge>> edgesByFromAndTo) {
        this.edgesByFromAndTo = edgesByFromAndTo;
    }

    // 구간이 하나도 없는 조회표. 어떤 어트랙션 간 이동도 찾을 수 없다.
    public static TravelTimeTable empty() {
        return EMPTY;
    }

    // 활성 구간만 넘겨야 한다. 비활성 구간은 호출자가 걸러 "데이터 없음"과 똑같이 다룬다.
    public static TravelTimeTable from(Collection<PathEdge> activeEdges) {
        Map<Long, Map<Long, PathEdge>> table = new HashMap<>();
        for (PathEdge edge : activeEdges) {
            table.computeIfAbsent(edge.fromAttractionId(), id -> new HashMap<>())
                    .put(edge.toAttractionId(), edge);
        }
        return new TravelTimeTable(table);
    }

    // fromAttractionId → toAttractionId 방향의 구간을 찾는다.
    // 방향별로 값이 다르므로 역방향(to → from) 값으로 대신하지 않고, 대체값도 만들지 않는다.
    public Optional<PathEdge> find(Long fromAttractionId, Long toAttractionId) {
        Map<Long, PathEdge> byTo = edgesByFromAndTo.get(fromAttractionId);
        if (byTo == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byTo.get(toAttractionId));
    }
}
