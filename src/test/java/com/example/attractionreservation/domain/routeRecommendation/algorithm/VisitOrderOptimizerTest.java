package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.RouteFixture;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.AMAZON;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.BUMPER_CAR;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.EXAMPLE_FIVE;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.MONIMO;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.SPACE_FIGHTER;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.THUNDER_FALLS;
import static org.assertj.core.api.Assertions.assertThat;

class VisitOrderOptimizerTest {

    private final VisitOrderOptimizer optimizer = new VisitOrderOptimizer();
    private final StartLegEstimator estimator = new HaversineStartLegEstimator(new RouteRecommendationProperties());

    // ===== 1. 완전 탐색 결과가 실제로 최소인지: 알고리즘과 다른 방식(5중 반복문)의 검증용 계산과 비교 =====

    static IntStream seeds() {
        return IntStream.range(0, 300);
    }

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("seeds")
    @DisplayName("무작위 비대칭 데이터 300세트에서 결과의 총 이동 시간 = 120가지를 모두 계산한 최솟값")
    void optimize_matchesIndependentExhaustiveMinimum(int seed) {
        // given: 방향별로 값이 다르고, 약 15% 구간이 빠진 무작위 데이터
        Random random = new Random(seed);
        List<Long> ids = List.of(11L, 22L, 33L, 44L, 55L);
        Map<Long, StartLeg> startLegs = new HashMap<>();
        for (Long id : ids) {
            int minutes = random.nextInt(16);
            startLegs.put(id, new StartLeg(minutes * 40, minutes * 60, minutes));
        }
        Map<Long, Map<Long, PathEdge>> edges = new HashMap<>();
        for (Long from : ids) {
            for (Long to : ids) {
                if (from.equals(to) || random.nextDouble() < 0.15) {
                    continue;
                }
                int minutes = 1 + random.nextInt(20);
                edges.computeIfAbsent(from, key -> new HashMap<>())
                        .put(to, new PathEdge(from, to, minutes * 50 + random.nextInt(100), minutes, false));
            }
        }

        // when
        VisitOrderResult result = optimizer.optimize(ids, startLegs, edges);
        Oracle oracle = Oracle.compute(ids, startLegs, edges);

        // then
        assertThat(result.evaluatedOrderCount()).isEqualTo(120);
        assertThat(result.validOrderCount()).isEqualTo(oracle.validCount);
        if (oracle.validCount == 0) {
            assertThat(result.hasValidOrder()).isFalse();
            return;
        }
        assertThat(result.totalMoveMinutes()).isEqualTo(oracle.minMinutes);
        // 결과가 말하는 합계가 실제 데이터로 다시 계산한 합계와 같은지 (합계를 지어내지 않았는지)
        assertThat(Oracle.cost(result.order(), startLegs, edges)).containsExactly(
                result.totalMoveMinutes(), result.totalDistanceM());
        // 모든 어트랙션을 정확히 한 번씩 방문
        assertThat(result.order()).containsExactlyInAnyOrderElementsOf(ids);
    }

    // ===== 2. 실측 데이터 회귀 테스트 (기대값은 Python 으로 따로 계산) =====

    @Test
    @DisplayName("요구사항 예시 5개(정정 후 좌표): 28분 동점 2개 중 거리가 짧은 범퍼카→썬더폴스→우주전투기→모니모→아마존")
    void realData_exampleFive_tieBrokenByDistance() {
        Map<Long, StartLeg> startLegs = startLegsFrom(RouteFixture.USER_CENTER, RouteFixture.LOCATIONS);

        VisitOrderResult result = optimizer.optimize(EXAMPLE_FIVE, startLegs, RouteFixture.edgesBetween(EXAMPLE_FIVE));

        // 동점 후보: [1,5,2,4,3] 28분 1478m / [5,1,2,4,3] 28분 1485m → 거리로 앞의 것을 고른다
        assertThat(result.order()).containsExactly(BUMPER_CAR, THUNDER_FALLS, SPACE_FIGHTER, MONIMO, AMAZON);
        assertThat(result.totalMoveMinutes()).isEqualTo(28);
        assertThat(result.totalDistanceM()).isEqualTo(1478);
        assertThat(result.validOrderCount()).isEqualTo(120);
    }

    @Test
    @DisplayName("정정 전 좌표(kan-97)에서는 '가까운 곳부터'(그리디)가 29분으로 지고, 완전 탐색은 28분을 찾는다")
    void realData_greedyLoses_butExhaustiveFindsMinimum() {
        Map<Long, GeoPoint> kan97Locations = new HashMap<>(RouteFixture.LOCATIONS);
        kan97Locations.put(THUNDER_FALLS, RouteFixture.THUNDER_FALLS_KAN97);
        kan97Locations.put(SPACE_FIGHTER, RouteFixture.SPACE_FIGHTER_KAN97);
        Map<Long, StartLeg> startLegs = startLegsFrom(RouteFixture.USER_CENTER, kan97Locations);
        Map<Long, Map<Long, PathEdge>> edges = RouteFixture.edgesBetween(EXAMPLE_FIVE);

        VisitOrderResult result = optimizer.optimize(EXAMPLE_FIVE, startLegs, edges);
        int greedyMinutes = greedyMinutes(EXAMPLE_FIVE, startLegs, edges);

        // 그리디는 첫 구간이 가장 짧은 우주전투기(4분)부터 가서 전체 29분이 된다
        assertThat(greedyMinutes).isEqualTo(29);
        assertThat(result.totalMoveMinutes()).isEqualTo(28).isLessThan(greedyMinutes);
        assertThat(result.order()).containsExactly(BUMPER_CAR, THUNDER_FALLS, SPACE_FIGHTER, MONIMO, AMAZON);
    }

    // ===== 3. 구간 데이터 처리 =====

    @Test
    @DisplayName("구간이 없거나 비활성이면 그 구간을 지나는 순서를 빼고 계산한다 (1→5 없음: 120 - 4! = 96가지)")
    void missingEdge_excludesOrdersUsingIt() {
        Map<Long, StartLeg> startLegs = startLegsFrom(RouteFixture.USER_CENTER, RouteFixture.LOCATIONS);
        Map<Long, Map<Long, PathEdge>> edges = RouteFixture.edgesBetween(EXAMPLE_FIVE);
        edges.get(BUMPER_CAR).remove(THUNDER_FALLS);   // 범퍼카 → 썬더폴스만 제거 (역방향은 그대로)

        VisitOrderResult result = optimizer.optimize(EXAMPLE_FIVE, startLegs, edges);

        assertThat(result.validOrderCount()).isEqualTo(96);
        // 역방향(썬더폴스 → 범퍼카, 3분)은 쓸 수 있으므로 그 방향으로 지나가는 2순위 경로가 선택된다
        assertThat(result.order()).containsExactly(THUNDER_FALLS, BUMPER_CAR, SPACE_FIGHTER, MONIMO, AMAZON);
        assertThat(result.totalMoveMinutes()).isEqualTo(28);
        assertThat(result.totalDistanceM()).isEqualTo(1485);
        assertThat(result.edges()).noneMatch(edge ->
                edge.fromAttractionId().equals(BUMPER_CAR) && edge.toAttractionId().equals(THUNDER_FALLS));
    }

    @Test
    @DisplayName("방향별 값을 섞지 않는다: A→B 가 1분, B→A 가 10분이면 A→B 순서를 고른다")
    void usesDirectionalEdges() {
        List<Long> ids = List.of(1L, 2L);
        Map<Long, StartLeg> startLegs = Map.of(1L, new StartLeg(100, 168, 4), 2L, new StartLeg(100, 168, 4));
        Map<Long, Map<Long, PathEdge>> edges = Map.of(
                1L, Map.of(2L, new PathEdge(1L, 2L, 50, 1, false)),
                2L, Map.of(1L, new PathEdge(2L, 1L, 500, 10, false)));

        VisitOrderResult result = optimizer.optimize(ids, startLegs, edges);

        assertThat(result.order()).containsExactly(1L, 2L);
        assertThat(result.totalMoveMinutes()).isEqualTo(5);
    }

    @Test
    @DisplayName("모든 순서에 빠진 구간이 있으면 유효한 순서가 없다고 반환한다 (값을 지어내지 않는다)")
    void noEdges_noValidOrder() {
        Map<Long, StartLeg> startLegs = startLegsFrom(RouteFixture.USER_CENTER, RouteFixture.LOCATIONS);

        VisitOrderResult result = optimizer.optimize(EXAMPLE_FIVE, startLegs, Map.of());

        assertThat(result.hasValidOrder()).isFalse();
        assertThat(result.evaluatedOrderCount()).isEqualTo(120);
        assertThat(result.validOrderCount()).isZero();
    }

    @Test
    @DisplayName("시간과 거리가 모두 같으면 ID 사전순으로 골라 항상 같은 결과를 낸다")
    void fullTie_choosesLexicographicallySmallestIds() {
        List<Long> ids = List.of(3L, 1L, 2L);
        Map<Long, StartLeg> startLegs = Map.of(
                1L, new StartLeg(10, 10, 1), 2L, new StartLeg(10, 10, 1), 3L, new StartLeg(10, 10, 1));
        Map<Long, Map<Long, PathEdge>> edges = new HashMap<>();
        for (Long from : ids) {
            for (Long to : ids) {
                if (!from.equals(to)) {
                    edges.computeIfAbsent(from, key -> new HashMap<>()).put(to, new PathEdge(from, to, 100, 2, false));
                }
            }
        }

        VisitOrderResult result = optimizer.optimize(ids, startLegs, edges);

        assertThat(result.order()).containsExactly(1L, 2L, 3L);
        assertThat(result.validOrderCount()).isEqualTo(6);
    }

    // ===== 테스트 도우미 =====

    private Map<Long, StartLeg> startLegsFrom(GeoPoint user, Map<Long, GeoPoint> locations) {
        Map<Long, StartLeg> startLegs = new LinkedHashMap<>();
        for (Long id : EXAMPLE_FIVE) {
            startLegs.put(id, estimator.estimate(user, locations.get(id)));
        }
        return startLegs;
    }

    // 비교용 그리디: 매번 지금 위치에서 가장 빨리 갈 수 있는 곳으로 간다
    private int greedyMinutes(List<Long> ids, Map<Long, StartLeg> startLegs, Map<Long, Map<Long, PathEdge>> edges) {
        Set<Long> left = new HashSet<>(ids);
        Long current = null;
        int total = 0;
        while (!left.isEmpty()) {
            Long next = null;
            int nextMinutes = Integer.MAX_VALUE;
            for (Long candidate : ids) {
                if (!left.contains(candidate)) {
                    continue;
                }
                int minutes = current == null
                        ? startLegs.get(candidate).moveMinutes()
                        : edges.get(current).get(candidate).moveMinutes();
                if (minutes < nextMinutes) {
                    next = candidate;
                    nextMinutes = minutes;
                }
            }
            total += nextMinutes;
            left.remove(next);
            current = next;
        }
        return total;
    }

    /**
     * 검증용 계산: 5중 반복문으로 120가지를 직접 나열한다.
     * 알고리즘(재귀 백트래킹)과 구현 방식을 다르게 해, 같은 버그를 공유하지 않도록 한다.
     */
    private static final class Oracle {
        private int minMinutes = Integer.MAX_VALUE;
        private int validCount;

        static Oracle compute(List<Long> ids, Map<Long, StartLeg> startLegs, Map<Long, Map<Long, PathEdge>> edges) {
            Oracle oracle = new Oracle();
            for (int a = 0; a < 5; a++)
                for (int b = 0; b < 5; b++)
                    for (int c = 0; c < 5; c++)
                        for (int d = 0; d < 5; d++)
                            for (int e = 0; e < 5; e++) {
                                if (new HashSet<>(List.of(a, b, c, d, e)).size() != 5) {
                                    continue;
                                }
                                List<Long> order = List.of(ids.get(a), ids.get(b), ids.get(c), ids.get(d), ids.get(e));
                                int[] cost = cost(order, startLegs, edges);
                                if (cost == null) {
                                    continue;
                                }
                                oracle.validCount++;
                                oracle.minMinutes = Math.min(oracle.minMinutes, cost[0]);
                            }
            return oracle;
        }

        // {총 시간, 총 거리}. 구간이 하나라도 없으면 null
        static int[] cost(List<Long> order, Map<Long, StartLeg> startLegs, Map<Long, Map<Long, PathEdge>> edges) {
            StartLeg first = startLegs.get(order.get(0));
            int minutes = first.moveMinutes();
            int distance = first.distanceM();
            for (int i = 0; i < order.size() - 1; i++) {
                PathEdge edge = edges.getOrDefault(order.get(i), Map.of()).get(order.get(i + 1));
                if (edge == null) {
                    return null;
                }
                minutes += edge.moveMinutes();
                distance += edge.distanceM();
            }
            return new int[]{minutes, distance};
        }
    }
}
