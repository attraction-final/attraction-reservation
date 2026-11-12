package com.example.attractionreservation.domain.routeRecommendation.algorithm;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSearcherTest {
    private static final long A = 1L;
    private static final long B = 2L;

    private final RouteSearcher routeSearcher = new RouteSearcher();

    private static CandidateAttraction attraction(long id, String name, CandidateSlot... slots) {
        return new CandidateAttraction(id, name, List.of(slots));
    }

    private static CandidateSlot slot(long slotId, String startHhmm, String endHhmm) {
        return new CandidateSlot(slotId, at(startHhmm), at(endHhmm));
    }

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-24T" + hhmm + ":00");
    }

    // 현재 위치 → 어트랙션 첫 구간. 거리 값은 탐색에 쓰이지 않으므로 이동 시간만 의미가 있다.
    private static StartLeg leg(int moveMinutes) {
        return new StartLeg(moveMinutes * 50, moveMinutes * 60, moveMinutes);
    }

    // 실측(estimated = false) 구간
    private static PathEdge edge(long from, long to, int moveMinutes) {
        return new PathEdge(from, to, moveMinutes * 60, moveMinutes, false);
    }

    // 첫 구간과 모든 어트랙션 간 구간의 이동 시간을 "도착 어트랙션별 시간"으로 둔다.
    // 이동 시간이 도착지에만 달린 단순한 조건에서 슬롯 선택·순위 규칙을 검증할 때 쓴다.
    private RouteSearchResult searchWithMinutesByTarget(
            LocalDateTime startAt,
            LocalDateTime endAt,
            List<CandidateAttraction> candidates,
            Map<Long, Integer> minutesByTarget
    ) {
        Map<Long, StartLeg> startLegs = new HashMap<>();
        List<PathEdge> edges = new ArrayList<>();
        for (Map.Entry<Long, Integer> to : minutesByTarget.entrySet()) {
            startLegs.put(to.getKey(), leg(to.getValue()));
            for (Long from : minutesByTarget.keySet()) {
                if (!from.equals(to.getKey())) {
                    edges.add(edge(from, to.getKey(), to.getValue()));
                }
            }
        }
        return routeSearcher.search(startAt, endAt, candidates, startLegs, TravelTimeTable.from(edges));
    }

    private static List<Long> visitedIds(RouteSearchResult result) {
        return result.visits().stream().map(PlannedVisit::attractionId).toList();
    }

    private static List<Integer> moveMinutes(RouteSearchResult result) {
        return result.visits().stream().map(PlannedVisit::moveMinutes).toList();
    }

    // 13:00부터 5분 간격으로 10분짜리 슬롯을 만든다. 이동 시간만큼 바로 다음 슬롯을 탈 수 있게 해서
    // 총 소요 시간이 이동 시간에 의해서만 달라지도록 한다.
    private static CandidateSlot[] everyFiveMinutes(long slotIdBase) {
        List<CandidateSlot> slots = new ArrayList<>();
        LocalDateTime startsAt = at("13:00");
        for (int i = 0; i < 36; i++) {
            slots.add(new CandidateSlot(slotIdBase + i, startsAt, startsAt.plusMinutes(10)));
            startsAt = startsAt.plusMinutes(5);
        }
        return slots.toArray(CandidateSlot[]::new);
    }

    //실측 구간 이동 시간

    @Test
    @DisplayName("실측 구간이 방향마다 다르면 이동 시간이 짧은 방문 순서를 고른다")
    void search_MeasuredPath_ChoosesShorterDirection() {
        // given
        // 첫 구간은 둘 다 10분이라, 구간 시간이 같다면 동점 → ID가 작은 A가 먼저 온다.
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(10), B, leg(10));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(
                edge(A, B, 30),   // A → B 30분
                edge(B, A, 5)));  // B → A 5분

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        // B(13:10~13:20) → 5분 이동 → A(13:25~13:35)
        assertEquals(List.of(B, A), visitedIds(result));
        assertEquals(List.of(10, 5), moveMinutes(result));
        assertEquals(35, result.totalDurationMinutes());
    }

    @Test
    @DisplayName("구간 데이터가 없는 이동은 쓰지 않고 다른 순서를 고른다")
    void search_MissingEdge_SkipsThatOrder() {
        // given
        // A가 현재 위치에서 더 가깝지만 A → B 구간이 없다. 대체값이 있었다면 A → B 가 골라졌을 것이다.
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(2), B, leg(10));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(edge(B, A, 5)));

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        assertThat(result.visits()).extracting(PlannedVisit::attractionId).containsExactly(B, A);
        assertThat(moveMinutes(result)).containsExactly(10, 5);
    }

    @Test
    @DisplayName("구간이 하나도 없으면 한 곳만 방문하는 부분 경로가 된다")
    void search_NoEdges_SingleVisit() {
        // given
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(10), B, leg(10));

        // when
        RouteSearchResult result = routeSearcher.search(
                at("13:00"), at("18:00"), List.of(a, b), startLegs, TravelTimeTable.empty());

        // then
        assertThat(result.visits()).hasSize(1);
        assertThat(result.unreachableAttractionIds()).hasSize(1);
    }

    //현재 위치 첫 구간

    @Test
    @DisplayName("현재 위치에서 가까운 어트랙션이 첫 방문지가 된다")
    void search_StartLeg_ClosestAttractionFirst() {
        // given
        // 구간 시간이 같으면 동점 → ID가 작은 A가 먼저 온다. 현재 위치에서는 B가 2분, A가 15분 거리다.
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(15), B, leg(2));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(edge(A, B, 10), edge(B, A, 10)));

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        // B는 13:02 도착 → 13:05 슬롯, A는 B → A 구간 10분 이동
        assertEquals(List.of(B, A), visitedIds(result));
        assertEquals(List.of(2, 10), moveMinutes(result));
    }

    @Test
    @DisplayName("첫 이동은 현재 위치 추정값, 이후 이동은 구간의 estimated 값을 그대로 표시한다")
    void search_FirstLegFromStartLeg_MarkedEstimated() {
        // given
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(3), B, leg(20));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(edge(A, B, 5))); // 실측 구간

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        assertThat(visitedIds(result)).containsExactly(A, B);
        assertThat(result.visits().get(0).moveMinutes()).isEqualTo(startLegs.get(A).moveMinutes());
        assertThat(result.visits().get(0).moveEstimated()).isTrue();
        assertThat(result.visits().get(1).moveMinutes()).isEqualTo(5);
        assertThat(result.visits().get(1).moveEstimated()).isFalse(); // 실측 구간
    }

    @Test
    @DisplayName("is_estimated 인 구간을 쓴 이동은 추정으로 표시한다")
    void search_EstimatedEdge_MarkedEstimated() {
        // given
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(A, leg(3), B, leg(20));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(new PathEdge(A, B, 300, 5, true)));

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        assertThat(visitedIds(result)).containsExactly(A, B);
        assertThat(result.visits().get(1).moveEstimated()).isTrue();
    }

    @Test
    @DisplayName("첫 구간 정보가 없는 어트랙션은 첫 방문지가 될 수 없다")
    void search_NoStartLeg_NotFirst() {
        // given
        // A는 첫 구간이 없다. 첫 구간이 있었다면 ID가 작은 A가 먼저 왔을 것이다.
        CandidateAttraction a = attraction(A, "A", everyFiveMinutes(100L));
        CandidateAttraction b = attraction(B, "B", everyFiveMinutes(200L));
        Map<Long, StartLeg> startLegs = Map.of(B, leg(10));
        TravelTimeTable travelTimes = TravelTimeTable.from(List.of(edge(A, B, 5), edge(B, A, 5)));

        // when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), List.of(a, b), startLegs, travelTimes);

        // then
        assertThat(result.visits().get(0).attractionId()).isNotEqualTo(A);
        assertThat(visitedIds(result)).containsExactly(B, A);
        assertThat(moveMinutes(result)).containsExactly(10, 5);
    }


    //시간표 게산

    @Test
    @DisplayName("도착 후 가장 먼저 찰 수 있는 슬롯을 고르고 이동, 대기, 탑승 시간을 계산한다")
    void search_SingleAttraction_CalculateSchedule() {
        // given
        CandidateAttraction safari = attraction(102L, "사파리",
                slot(1L, "13:05", "13:25"),
                slot(2L, "13:30", "13:50"));

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("18:00"), List.of(safari), Map.of(102L, 10));

        // then
        assertEquals(1, result.visits().size());
        PlannedVisit visit = result.visits().get(0);
        assertEquals(1, visit.sequence());
        assertEquals(2L, visit.slotId());
        assertEquals(10, visit.moveMinutes());
        assertEquals(at("13:10"), visit.arrivalAt());
        assertEquals(20, visit.waitMinutes());
        assertEquals(20, visit.rideMinutes());
        assertEquals(at("13:50"), visit.endsAt());
        assertEquals(50, result.totalDurationMinutes());
    }

    @Test
    @DisplayName("도착 시각과  슬롯 시작 시각이 같으면 대기 0분으로 탑승할 수 있다.")
    void search_ArrivalEqualsSlotStart_zeroWait() {
        // given
        CandidateAttraction a = attraction(A, "A", slot(10L, "13:30", "13:40"));

        // when
        RouteSearchResult result = searchWithMinutesByTarget(at("13:00"), at("18:00"), List.of(a), Map.of(A, 30));

        // then
        assertEquals(1, result.visits().size());
        assertEquals(0, result.visits().get(0).waitMinutes());
    }


    @Test
    @DisplayName("5개 중 후보 4개, 3를 포함하는 경로와 방문별 시간표를 그대로 재현한다.")
    void search_ReproducesSchedule() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(101L, "T익스프레스", slot(9001L, "14:30", "14:40")),
                attraction(102L, "사파리", slot(9201L, "13:30", "13:50")),
                attraction(103L, "아마존 익스프레스", slot(9301L, "15:30", "15:40")),
                attraction(105L, "어트랙션 E", slot(9501L, "13:00", "13:10"))
        );
        Map<Long, Integer> minutesByTarget = Map.of(101L, 15, 102L, 10, 103L, 12, 105L, 5);

        // when
        RouteSearchResult result = searchWithMinutesByTarget(at("13:00"), at("18:00"), candidates, minutesByTarget);

        // then
        assertEquals(List.of(102L, 101L, 103L), visitedIds(result));
        assertEquals(List.of(1, 2, 3), result.visits().stream().map(PlannedVisit::sequence).toList());
        assertEquals(List.of(10, 15, 12), result.visits().stream().map(PlannedVisit::moveMinutes).toList());
        assertEquals(List.of(at("13:10"), at("14:05"), at("14:52")),
                result.visits().stream().map(PlannedVisit::arrivalAt).toList());
        assertEquals(List.of(20, 25, 38), result.visits().stream().map(PlannedVisit::waitMinutes).toList());
        assertEquals(List.of(20, 10, 10), result.visits().stream().map(PlannedVisit::rideMinutes).toList());
        assertEquals(160, result.totalDurationMinutes());
        assertEquals(List.of(105L), result.unreachableAttractionIds());
    }


    //최적 경로 선정

    @Test
    @DisplayName("이용 종료 시각을 넘기는 방문은 경로에 넣지 않는다.")
    void search_ExceedsEndAt_ExcludesAttraction() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(A, "A", slot(11L, "13:40", "13:50")),
                attraction(B, "B", slot(21L, "14:00", "14:10"))
        );

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("14:00"), candidates, Map.of(A, 5, B, 5));

        // then
        assertEquals(List.of(A), visitedIds(result));
        assertEquals(List.of(B), result.unreachableAttractionIds());
    }


    @Test
    @DisplayName("가장 이른 슬롯부터 타는 단순 탐욕 방식보다 더 많은 어트랙션을 포함하는 순서를 찾는다")
    void search_GreedyFails_FindsMoreAttraction() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(A, "A", slot(11L, "13:05", "13:25"), slot(12L, "13:30", "13:50")),
                attraction(B, "B", slot(21L, "13:10", "13:20"))
        );

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("14:00"), candidates, Map.of(A, 5, B, 5));

        // then
        assertEquals(List.of(B, A), visitedIds(result));
        assertEquals(12L, result.visits().get(1).slotId());
        assertTrue(result.unreachableAttractionIds().isEmpty());
    }

    @Test
    @DisplayName("포함된 어트랙션 수가 같으면 총 소요 시간이 짦은 경롤를 고른다.")
    void search_SameCount_PicksShorterDuration() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(A, "A", slot(11L, "13:05", "13:15"), slot(12L, "13:25", "13:35")),
                attraction(B, "B", slot(21L, "13:10", "13:20"), slot(22L, "14:00", "14:10"))
        );

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("18:00"), candidates, Map.of(A, 5, B, 5));

        // then
        assertEquals(List.of(B, A), visitedIds(result));
        assertEquals(35, result.totalDurationMinutes());
    }

    @Test
    @DisplayName("개수와 소요 시간이 같으면 첫 슬롯이 이른 경로를 고른다")
    void search_SameCountAndDuration_PicksEarlierFirstSlot() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(A, "B", slot(11L, "13:20", "13:30"), slot(12L, "13:40", "13:50")),
                attraction(B, "A", slot(21L, "13:10", "13:20"), slot(22L, "13:40", "13:50"))
        );

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("18:00"), candidates, Map.of(A, 5, B, 5));

        // then
        assertEquals(List.of(B, A), visitedIds(result));
        assertEquals(at("13:10"), result.visits().get(0).startsAt());
        // 보완 필요:
        // 현재는 A의 ID도 더 작다.
        //
        // 따라서 첫 시작 비교가 빠져 있어도
        // ID 사전순 비교 때문에 같은 결과가 나올 수 있다.
    }

    @Test
    @DisplayName("마지막 슬롯 종료가 이용 종료 시각과 같으면 포함한다.")
    void search_SlotEndEqualsEndAt_Included() {
        // given
        CandidateAttraction a = attraction(A, "A", slot(11L, "13:50", "14:00"));

        // when
        RouteSearchResult result = searchWithMinutesByTarget(at("13:00"), at("14:00"), List.of(a), Map.of(A, 5));

        // then
        assertEquals(List.of(A), visitedIds(result));
    }


    //결과 없음

    @Test
    @DisplayName("어떤 어트랙션도 시간 안에 도착할 수 없으면 빈 경로와 전체 미포함 목록을 반환한다.")
    void search_NothingReachable_ReturnsEmpty() {
        // given
        List<CandidateAttraction> candidates = List.of(
                attraction(A, "A", slot(11L, "13:05", "13:15")),
                attraction(B, "B", slot(21L, "13:00", "13:10"))
        );

        // when
        RouteSearchResult result = searchWithMinutesByTarget(
                at("13:00"), at("18:00"), candidates, Map.of(A, 10, B, 10));

        // then
        assertTrue(result.isEmpty());
        assertEquals(List.of(A, B), result.unreachableAttractionIds());
        assertEquals(0, result.totalDurationMinutes());
    }

    @Test
    @DisplayName("후보가 없으면 빈 결과를 반환한다.")
    void search_NoCandidates_ReturnsEmpty() {
        // given
        List<CandidateAttraction> candidates = List.of();

        // when
        RouteSearchResult result = routeSearcher.search(
                at("13:00"), at("18:00"), candidates, Map.of(), TravelTimeTable.empty());

        // then
        assertTrue(result.isEmpty());
        assertTrue(result.unreachableAttractionIds().isEmpty());
    }


    //성능

    @Test
    @DisplayName("어트랙션 5개 , 슬록 각 40개 여도 1초 안에 끝난다.")
    void search_FiveAttractionsManySlots_FinishesFast() {
        // given
        List<CandidateAttraction> candidates = new ArrayList<>();
        Map<Long, Integer> minutesByTarget = new HashMap<>();
        for (long id = 1; id <= 5; id++) {
            List<CandidateSlot> slots = new ArrayList<>();
            LocalDateTime start = at("10:00").plusMinutes(id);
            for (int i = 0; i < 40; i++) {
                LocalDateTime s = start.plusMinutes(i * 15L);
                slots.add(new CandidateSlot(id * 1000 + i, s, s.plusMinutes(10)));
            }
            candidates.add(new CandidateAttraction(id, "A" + id, slots));
            minutesByTarget.put(id, (int) (3 + id));
        }

        // when
        long begin = System.nanoTime();
        RouteSearchResult result = searchWithMinutesByTarget(at("10:00"), at("22:00"), candidates, minutesByTarget);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        // then
        assertEquals(5, result.visits().size());
        assertTrue(elapsedMillis < 1000, "elapsed" + elapsedMillis + "ms");
    }
}