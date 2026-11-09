package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

// 어트랙션 간 실측 이동 시간 조회표
// 추천 1건당 구간을 DB에서 한 번만 읽어 이 조회표로 만들고, 탐색 중에는 메모리에서만 조회한다.
// (5개 선택 시 방문 순서 120가지 × 구간마다 DB를 조회하면 N+1이 된다)
public final class TravelTimeTable {

    private static final TravelTimeTable EMPTY = new TravelTimeTable(Map.of());

    // 출발 어트랙션 ID → (도착 어트랙션 ID → 이동 시간(분))
    // 활성 구간만 담는다  비활성 구간은 "데이터 없음"과 똑같이 대체값을 쓰게 한다
    private final Map<Long, Map<Long, Integer>> minutesByFromAndTo;

    private TravelTimeTable(Map<Long, Map<Long, Integer>> minutesByFromAndTo) {
        this.minutesByFromAndTo = minutesByFromAndTo;
    }

    // 구간 데이터가 없을 때 사용 모든 이동이 대체값(정문 기준 시간)으로 계산된다.
    public static TravelTimeTable empty() {
        return EMPTY;
    }

    public static TravelTimeTable from(Collection<PathTime> paths) {
        Map<Long, Map<Long, Integer>> table = new HashMap<>();
        for (PathTime path : paths) {
            if (!path.active()) {
                continue;
            }
            table.computeIfAbsent(path.fromAttractionId(), id -> new HashMap<>())
                    .put(path.toAttractionId(), path.moveMinutes());
        }
        return new TravelTimeTable(table);
    }

    // fromAttractionId → toAttractionId 이동 시간을 반환한다.
    // - fromAttractionId가 null이면 정문에서 출발하는 첫 이동이므로 fallbackMinutes(정문 기준 시간)를 쓴다.
    // - 구간이 없거나 비활성이면 fallbackMinutes를 쓴다.
    // 방향별로 값이 다르므로 (from, to) 순서를 바꿔 조회하지 않는다.
    public int minutesBetween(Long fromAttractionId, Long toAttractionId, int fallbackMinutes) {
        if (fromAttractionId == null) {
            return fallbackMinutes;
        }
        Map<Long, Integer> byTo = minutesByFromAndTo.get(fromAttractionId);
        if (byTo == null) {
            return fallbackMinutes;
        }
        return byTo.getOrDefault(toAttractionId, fallbackMinutes);
    }

    // 조회표를 만들 때 필요한 구간 정보만 담은 스냅샷 (엔티티에 의존하지 않기 위해 따로 둔다)
    public record PathTime(
            Long fromAttractionId,
            Long toAttractionId,
            int moveMinutes,
            boolean active
    ) {
    }
}