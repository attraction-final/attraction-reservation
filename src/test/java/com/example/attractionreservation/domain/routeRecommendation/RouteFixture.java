package com.example.attractionreservation.domain.routeRecommendation;

import com.example.attractionreservation.domain.routeRecommendation.algorithm.GeoPoint;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.PathEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트용 실측 데이터: docs/sql/kan-97-route-travel-data-migration.sql 의 값을 그대로 옮겼다.
 * 좌표는 kan-98 정정 후 값이다. 실제 DB의 id 는 다를 수 있어 테스트에서는 1~6 으로 둔다.
 *
 *   1 범퍼카, 2 우주전투기, 3 아마존 익스프레스, 4 모니모 러쉬, 5 썬더폴스, 6 허리케인
 */
public final class RouteFixture {

    public static final long BUMPER_CAR = 1L;
    public static final long SPACE_FIGHTER = 2L;
    public static final long AMAZON = 3L;
    public static final long MONIMO = 4L;
    public static final long THUNDER_FALLS = 5L;
    public static final long HURRICANE = 6L;

    public static final Map<Long, String> NAMES = Map.of(
            BUMPER_CAR, "범퍼카",
            SPACE_FIGHTER, "우주전투기",
            AMAZON, "아마존 익스프레스",
            MONIMO, "모니모 러쉬",
            THUNDER_FALLS, "썬더폴스",
            HURRICANE, "허리케인");

    // kan-98 정정 후 좌표
    public static final Map<Long, GeoPoint> LOCATIONS = Map.of(
            BUMPER_CAR, new GeoPoint(37.2923658, 127.1995663),
            SPACE_FIGHTER, new GeoPoint(37.2918125, 127.2011406),
            AMAZON, new GeoPoint(37.2912666, 127.2057353),
            MONIMO, new GeoPoint(37.29, 127.2025),
            THUNDER_FALLS, new GeoPoint(37.2936625, 127.1985781),
            HURRICANE, new GeoPoint(37.29397, 127.20161));

    // kan-97 정정 전 좌표 (붙여준 SQL 그대로). 썬더폴스·우주전투기만 다르다.
    public static final GeoPoint THUNDER_FALLS_KAN97 = new GeoPoint(37.2936, 127.2036);
    public static final GeoPoint SPACE_FIGHTER_KAN97 = new GeoPoint(37.2929, 127.2024);

    // {from, to, distance_m, move_minutes} — 네이버 지도 도보 길찾기 측정값 (kan-97)
    public static final long[][] PATHS = {
            {1, 2, 322, 5}, {1, 3, 641, 11}, {1, 4, 632, 11}, {1, 5, 141, 3}, {1, 6, 342, 9},
            {2, 1, 322, 5}, {2, 3, 433, 10}, {2, 4, 424, 9}, {2, 5, 452, 8}, {2, 6, 431, 9},
            {3, 1, 641, 12}, {3, 2, 433, 10}, {3, 4, 217, 3}, {3, 5, 808, 16}, {3, 6, 827, 19},
            {4, 1, 632, 12}, {4, 2, 424, 10}, {4, 3, 217, 3}, {4, 5, 799, 16}, {4, 6, 818, 19},
            {5, 1, 141, 3}, {5, 2, 452, 8}, {5, 3, 808, 14}, {5, 4, 799, 14}, {5, 6, 271, 6},
            {6, 1, 342, 6}, {6, 2, 431, 7}, {6, 3, 827, 16}, {6, 4, 818, 16}, {6, 5, 271, 4},
    };

    // 요구사항 예시의 5개: 범퍼카, 우주전투기, 아마존 익스프레스, 모니모 러쉬, 썬더폴스
    public static final List<Long> EXAMPLE_FIVE = List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON, MONIMO, THUNDER_FALLS);

    // 테스트에 쓰는 사용자 위치 2곳
    public static final GeoPoint USER_CENTER = new GeoPoint(37.2930, 127.2010);
    public static final GeoPoint USER_NEAR_AMAZON = new GeoPoint(37.2905, 127.2050);

    private RouteFixture() {
    }

    /** 선택한 어트랙션끼리의 활성 구간 행렬 (출발 → 도착 → 구간) */
    public static Map<Long, Map<Long, PathEdge>> edgesBetween(List<Long> attractionIds) {
        Map<Long, Map<Long, PathEdge>> edges = new HashMap<>();
        for (long[] row : PATHS) {
            if (attractionIds.contains(row[0]) && attractionIds.contains(row[1])) {
                edges.computeIfAbsent(row[0], key -> new LinkedHashMap<>())
                        .put(row[1], new PathEdge(row[0], row[1], (int) row[2], (int) row[3], false));
            }
        }
        return edges;
    }

    /** 선택한 어트랙션끼리의 구간 행 목록 */
    public static List<long[]> pathRowsBetween(List<Long> attractionIds) {
        List<long[]> rows = new ArrayList<>();
        for (long[] row : PATHS) {
            if (attractionIds.contains(row[0]) && attractionIds.contains(row[1])) {
                rows.add(row);
            }
        }
        return rows;
    }
}
