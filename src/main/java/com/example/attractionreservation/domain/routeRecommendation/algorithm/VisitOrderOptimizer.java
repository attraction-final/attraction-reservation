package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

//완전 탐색(Brute Force)으로 총 이동 시간이 가장 짧은 방문 순서를 찾는다.
//비용 = (사용자 위치 → 1번째) + (1번째 → 2번째) + ... + (n-1번째 → n번째)
 // - 첫 구간: StartLeg (좌표 기반 추정)
 //  - 나머지 구간: attraction_paths 의 활성 구간 (출발 → 도착 방향 그대로)
//- 마지막 어트랙션에서 끝난다. 출발 위치로 돌아오는 구간은 계산하지 않는다.

 // 왜 완전 탐색인가:
 //   n = 5 이면 순서는 5! = 120 가지, 구간 조회는 120 × 5 = 600 번이라 즉시 끝난다.
 //   "가장 가까운 곳부터"(그리디)는 앞에서 이득을 보고 뒤에서 크게 손해 보는 경우를 놓친다.
 //   n 이 10 이상으로 커지면(10! = 3,628,800) Held-Karp DP(O(n²·2ⁿ))로 바꾸는 것을 검토한다.

 // 구간 데이터가 없는(또는 비활성인) 순서는 무효로 보고 건너뛴다.
 //없는 값을 역방향 값이나 추정값으로 대신하지 않는다.

 // 동점 처리 (같은 요청에 항상 같은 응답을 주기 위해):
 //   1순위 총 이동 시간 → 2순위 총 이동 거리 → 3순위 방문 순서의 어트랙션 ID 사전순
@Component
public class VisitOrderOptimizer {

    public VisitOrderResult optimize(
            List<Long> attractionIds, //방문할 어트랙션 ID (중복 없음)
            Map<Long, StartLeg> startLegs,
            Map<Long, Map<Long, PathEdge>> activeEdges //출발 ID → (도착 ID → 활성 구간) 비활성,누락 구간은 들어 있지 않아야 한다
    ) {
        SearchState state = new SearchState(attractionIds, startLegs, activeEdges);
        permute(state, new ArrayList<>(), new boolean[attractionIds.size()]);

        if (state.bestOrder == null) {
            return VisitOrderResult.noValidOrder(state.evaluatedCount);
        }
        return new VisitOrderResult(
                List.copyOf(state.bestOrder),
                startLegs.get(state.bestOrder.get(0)),
                List.copyOf(state.bestEdges),
                state.bestMinutes,
                state.bestDistance,
                state.evaluatedCount,
                state.validCount
        );
    }

    //백트래킹으로 모든 순열을 만든다.
    //current 에 아직 안 쓴 어트랙션을 하나씩 붙여 보고, 다 붙이면(길이 n) 그 순서를 평가한 뒤 되돌린다.
    private void permute(SearchState state, List<Long> current, boolean[] used) {
        if (current.size() == state.attractionIds.size()) {
            evaluate(state, current);
            return;
        }
        for (int i = 0; i < state.attractionIds.size(); i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            current.add(state.attractionIds.get(i));

            permute(state, current, used);

            // 백트래킹: 방금 선택을 되돌리고 다음 후보를 같은 상태에서 시도한다.
            current.remove(current.size() - 1);
            used[i] = false;
        }
    }

    //완성된 순서 하나의 총 비용을 계산하고, 지금까지의 최선보다 좋으면 교체한다
    private void evaluate(SearchState state, List<Long> order) {
        state.evaluatedCount++;

        StartLeg startLeg = state.startLegs.get(order.get(0));
        if (startLeg == null) {
            return; // 첫 구간을 계산할 수 없는 어트랙션 (좌표 없음) → 무효
        }
        int minutes = startLeg.moveMinutes();
        int distance = startLeg.distanceM();
        List<PathEdge> edges = new ArrayList<>(order.size() - 1);

        for (int i = 0; i < order.size() - 1; i++) {
            PathEdge edge = findEdge(state.activeEdges, order.get(i), order.get(i + 1));
            if (edge == null) {
                return; // 이 순서에 필요한 구간이 없거나 비활성 → 무효 (값을 만들어내지 않는다)
            }
            minutes += edge.moveMinutes();
            distance += edge.distanceM();
            edges.add(edge);
        }

        state.validCount++;
        if (isBetter(minutes, distance, order, state)) {
            state.bestOrder = new ArrayList<>(order);
            state.bestEdges = edges;
            state.bestMinutes = minutes;
            state.bestDistance = distance;
        }
    }

    // from → to 방향의 구간만 찾는다. 역방향(to → from) 값으로 대신하지 않는다.
    private PathEdge findEdge(Map<Long, Map<Long, PathEdge>> activeEdges, Long from, Long to) {
        Map<Long, PathEdge> fromEdges = activeEdges.get(from);
        return fromEdges == null ? null : fromEdges.get(to);
    }

    private boolean isBetter(int minutes, int distance, List<Long> order, SearchState state) {
        if (state.bestOrder == null) {
            return true;
        }
        if (minutes != state.bestMinutes) {
            return minutes < state.bestMinutes;
        }
        if (distance != state.bestDistance) {
            return distance < state.bestDistance;
        }
        for (int i = 0; i < order.size(); i++) {
            int comparison = Long.compare(order.get(i), state.bestOrder.get(i));
            if (comparison != 0) {
                return comparison < 0;
            }
        }
        return false;
    }

    // 한 번의 탐색 동안만 쓰는 상태. 컴포넌트(싱글톤) 필드에 두지 않아 동시 요청에도 안전하다
    private static final class SearchState {
        private final List<Long> attractionIds;
        private final Map<Long, StartLeg> startLegs;
        private final Map<Long, Map<Long, PathEdge>> activeEdges;

        private List<Long> bestOrder;
        private List<PathEdge> bestEdges;
        private int bestMinutes;
        private int bestDistance;
        private int evaluatedCount;
        private int validCount;

        private SearchState(List<Long> attractionIds,
                            Map<Long, StartLeg> startLegs,
                            Map<Long, Map<Long, PathEdge>> activeEdges
        ) {
            this.attractionIds = attractionIds;
            this.startLegs = startLegs;
            this.activeEdges = activeEdges;
        }
    }
}
