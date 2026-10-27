package com.example.attractionreservation.domain.routeRecommendation.algorithm;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSearcherTest {
    private final RouteSearcher routeSearcher = new RouteSearcher();

    private static CandidateAttraction attraction(long id, String name, int moveMinutes, CandidateSlot... slots){
        return new CandidateAttraction(id, name ,moveMinutes, List.of(slots));
    }

    private static CandidateSlot slot (long slotId, String startHhmm, String endHhmm) {
        return new CandidateSlot(slotId, at(startHhmm), at(endHhmm));
    }

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-24T" + hhmm + ":00");
    }

    private static List<Long> visitedIds(RouteSearchResult result) {
        return result.visits().stream().map(PlannedVisit::attractionId).toList();
    }




    //시간표 게산

    @Test
    @DisplayName("도착 후 가장 먼저 찰 수 있는 슬롯을 고르고 이동, 대기, 탑승 시간을 계산한다")
    void search_SingleAttraction_CalculateSchedule() {
        //given
        CandidateAttraction safari = attraction(102L, "사파리", 10,
                slot(1L, "13:05", "13:25"),
                slot(2L, "13:30", "13:50"));

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"),List.of(safari));

        //then
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
        //given
        CandidateAttraction a = attraction(1L, "A", 30, slot(10L, "13:30", "13:40"));

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"),List.of(a));

        //then
        assertEquals(1, result.visits().size());
        assertEquals(0, result.visits().get(0).waitMinutes());
    }


    @Test
    @DisplayName("5개 중 후보 4개, 3를 포함하는 경로와 방문별 시간표를 그대로 재현한다.")
    void search_ReproducesSchedule() {
        //given
        List<CandidateAttraction> candidates = List.of(
                attraction(101L, "T익스프레스", 15, slot(9001L, "14:30", "14:40")),
                attraction(102L, "사파리", 10, slot(9201L, "13:30", "13:50")),
                attraction(103L, "아마존 익스프레스", 12, slot(9301L, "15:30", "15:40")),
                attraction(105L, "어트랙션 E", 5, slot(9501L, "13:00", "13:10"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), candidates);

        //then
        assertEquals(List.of(102L,101L,103L), visitedIds(result));
        assertEquals(List.of(1,2,3), result.visits().stream().map(PlannedVisit::sequence).toList());
        assertEquals(List.of(10,15,12), result.visits().stream().map(PlannedVisit::moveMinutes).toList());
        assertEquals(List.of(at("13:10"), at("14:05"), at("14:52")),
                result.visits().stream().map(PlannedVisit::arrivalAt).toList());
        assertEquals(List.of(20,25,38), result.visits().stream().map(PlannedVisit::waitMinutes).toList());
        assertEquals(List.of(20,10,10), result.visits().stream().map(PlannedVisit::rideMinutes).toList());
        assertEquals(160, result.totalDurationMinutes());
        assertEquals(List.of(105L), result.unreachableAttractionIds());
    }




    //최적 경로 선정

    @Test
    @DisplayName("이용 종료 시각을 넘기는 방문은 경로에 넣지 않는다.")
    void search_ExceedsEndAt_ExcludesAttraction() {
        //given
        List<CandidateAttraction> candidates = List.of(
                attraction(1L, "A", 5, slot(11L, "13:40", "13:50")),
                attraction(2L, "B", 5, slot(21L, "14:00", "14:10"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("14:00"), candidates);

        //then
        assertEquals(List.of(1L), visitedIds(result));
        assertEquals(List.of(2L), result.unreachableAttractionIds());

    }


    @Test
    @DisplayName("가장 이른 슬롯부터 타는 단순 탐욕 방식보다 더 많은 어트랙션을 포함하는 순서를 찾는다")
    void search_GreedyFails_FindsMoreAttraction() {
        //given
        List<CandidateAttraction> candidates = List.of(
                attraction(1L, "A", 5, slot(11L, "13:05", "13:25"), slot(12L, "13:30", "13:50")),
                attraction(2L, "B", 5, slot(21L, "13:10", "13:20"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("14:00"), candidates);

        //then
        assertEquals(List.of(2L,1L), visitedIds(result));
        assertEquals(12L, result.visits().get(1).slotId());
        assertTrue(result.unreachableAttractionIds().isEmpty());
    }

    @Test
    @DisplayName("포함된 어트랙션 수가 같으면 총 소요 시간이 짦은 경롤를 고른다.")
    void search_SameCount_PicksShorterDuration() {
        //given
        List<CandidateAttraction> candidates = List.of(
                attraction(1L, "A", 5, slot(11L, "13:05", "13:15"), slot(12L, "13:25", "13:35")),
                attraction(2L, "B", 5, slot(21L, "13:10", "13:20"), slot(22L, "14:00", "14:10"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), candidates);

        //then
        assertEquals(List.of(2L,1L) ,visitedIds(result));
        assertEquals(35, result.totalDurationMinutes());
    }

    @Test
    @DisplayName("개수와 소요 시간이 같으면 첫 슬롯이 이른 경로를 고른다")
    void search_SameCountAndDuration_PicksEarlierFirstSlot() {
        //given
        List<CandidateAttraction> candidates = List.of(
                attraction(1L, "B", 5, slot(11L, "13:20", "13:30"), slot(12L, "13:40", "13:50")),
                attraction(2L, "A", 5, slot(21L, "13:10", "13:20"), slot(22L, "13:40", "13:50"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), candidates);

        //then
        assertEquals(List.of(2L, 1L), visitedIds(result));
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
        //given
        CandidateAttraction a = attraction(1L, "A", 5, slot(11L, "13:50", "14:00"));

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("14:00"), List.of(a));

        //then
        assertEquals(List.of(1L), visitedIds(result));

    }


    //결과 없음

    @Test
    @DisplayName("어떤 어트랙션도 시간 안에 도착할 수 없으면 빈 경로와 전체 미포함 목록을 반환한다.")
    void search_NothingReachable_ReturnsEmpty() {
        //given
        List<CandidateAttraction> candidates =List.of(
                attraction(1L, "A", 10, slot(11L, "13:05", "13:15")),
                attraction(2L, "B", 10, slot(21L, "13:00", "13:10"))
        );

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), candidates);

        //then
        assertTrue(result.isEmpty());
        assertEquals(List.of(1L,2L), result.unreachableAttractionIds());
        assertEquals(0, result.totalDurationMinutes());
        }

    @Test
    @DisplayName("후보가 없으면 빈 결과를 반환한다.")
    void search_NoCandidates_ReturnsEmpty() {
        //given
        List<CandidateAttraction> candidates = List.of();

        //when
        RouteSearchResult result = routeSearcher.search(at("13:00"), at("18:00"), candidates);

        //then
        assertTrue(result.isEmpty());
        assertTrue(result.unreachableAttractionIds().isEmpty());
    }



    //성능

    @Test
    @DisplayName("어트랙션 5개 , 슬록 각 40개 여도 1초 안에 끝난다.")
    void search_FiveAttractionsManySlots_FinishesFast() {
        //given
        List<CandidateAttraction> candidates = new ArrayList<>();
        for (long id = 1; id <=5; id++) {
            List<CandidateSlot> slots = new ArrayList<>();
            LocalDateTime start = at("10:00").plusMinutes(id);
            for (int i = 0; i < 40; i++) {
                LocalDateTime s = start.plusMinutes(i * 15L);
                slots.add(new CandidateSlot(id * 1000 + i, s, s.plusMinutes(10)));
            }
            candidates.add(new CandidateAttraction(id,"A" + id, (int) (3 + id), slots));
        }

        //when
        long begin = System.nanoTime();
        RouteSearchResult result = routeSearcher.search(at("10:00"), at("22:00"), candidates);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000;

        //then
        assertEquals(5, result.visits().size());
        assertTrue(elapsedMillis < 1000, "elapsed" + elapsedMillis +"ms");

    }
    }


