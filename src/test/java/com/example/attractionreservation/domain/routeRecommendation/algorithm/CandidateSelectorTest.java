package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.algorithm.*;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CandidateSelectorTest {
    // Spring 기능에 의존하지 않는 후보 선정 로직이라 객체를 직접 생성해서 테스트함.
  private final CandidateSelector candidateSelector =new  CandidateSelector();

  private  static  final LocalDateTime  STRAT_AT = at("13:00");
  private  static  final LocalDateTime END_AT = at("18:00");
  private  static  final LocalDateTime NOW = at("12:00");

    //헬퍼
    private static  LocalDateTime at(String hhmm) {
    return LocalDateTime.parse("2026-10-22T" + hhmm + ":00");
    }

    private static AttractionView operating(Long id, String name, int moveMinutes) {
        return new AttractionView(id, name,true, moveMinutes);
    }

    private static SlotView openSlot(Long slotId, String startHhmm , String endHhmm) {
        return new SlotView(slotId,at(startHhmm), at(endHhmm), SlotState.OPEN, 20, 0);
    }

    private CandidateSelection selectOne(AttractionView attraction, List<SlotView> slots, LocalDateTime now) {
        return candidateSelector.select(
                List.of(attraction) , Map.of(attraction.attractionId(), slots), STRAT_AT,END_AT,now);
    }
    private static ExclusionReason onlyReason(CandidateSelection selection) {
        assertTrue(selection.candidates().isEmpty());
        assertEquals(1, selection.exclusions().size());
        return selection.exclusions().get(0).reason();
    }

    private static SlotView slot(long slotId, String startHhmm , String endHhmm, SlotState state, int capacity, int reserved) {
        return new SlotView(slotId,at(startHhmm), at(endHhmm), state, capacity, reserved);
    }


    //정상
    @Test
    @DisplayName("조건을 통과한 슬롯만 시작 시각 오름차순으로 후보에 담고 이동 시간을 옮겨 담는다.")
    void select_ValidSlots_SortedCandidates() {
        //given
        AttractionView a = operating(1L,"A",8);
        List<SlotView> slots = List.of(
                openSlot(3L,"15:00" ,"15:10"),
                openSlot(1L, "13:30", "13:40"),
                openSlot(2L, "12:30", "12:40")
        );
        //when
        CandidateSelection selection = selectOne(a, slots, NOW);
        //then
        assertEquals(1, selection.candidates().size());
        CandidateAttraction candidate = selection.candidates().get(0);
        assertEquals(8, candidate.moveMinutes());
        assertEquals(List.of(1L,3L), candidate.slots().stream().map(CandidateSlot::slotId).toList());
        assertTrue(selection.exclusions().isEmpty());
    }

    @Test
    @DisplayName("후보의 제외 목록 모두 요청한 어트랙션 순서를 유지한다")
    void select_KeepRequestOrder() {
        //given
        AttractionView c = operating(3L,"C",5);
        AttractionView a = new AttractionView(1L, "A", false, 5);
        AttractionView b = operating(2L, "B",5);
        Map<Long,List<SlotView>> slots = Map.of(
                3L, List.of(openSlot(31L,"14:00","14:10")),
                1L, List.of(openSlot(11L, "14:00", "14:10")),
                2L, List.of(openSlot(21L, "14:00", "14:10")));
        //when
        CandidateSelection selection = candidateSelector.select(List.of(c,a,b), slots, STRAT_AT,END_AT,NOW);

        //then
        assertEquals(List.of(3L,2L),
                selection.candidates().stream().map(CandidateAttraction::attractionId).toList());
        assertEquals(List.of(1L),
                selection.exclusions().stream().map(Exclusion::attractionId).toList());
        //제외된 어트랙션이 하나뿐, 이 테스트만으로는 여러 제외 항목 사이의 순서 유지까지 증명못함 -> 근데 제외 항목 사이의 순서 유지가 필요한가?
    }

    //제외 사유

    @Test
    @DisplayName("운영중인 아닌 어트랙션은 슬롯과 관계없이 NOT_OPERATING 으로 제외한다")
    void select_NotOperating_Excluded() {
        //given
        AttractionView a = new AttractionView(1L, "A", false, 5);

        //when
        CandidateSelection selection = selectOne(a, List.of(openSlot(1L,"14:00","14:10")), NOW);

        //then
        assertEquals(ExclusionReason.NOT_OPERATING, onlyReason(selection));
        assertEquals("A", selection.exclusions().get(0).attractionName());
    }

    @Test
    @DisplayName("이용 시간 밖의 슬롯만 있으면 OUT_OF_TIME_RANGE")
    void select_OutOfTimeRange_Excluded() {
        //given
        AttractionView a = operating(1L,"A",5);
        List<SlotView> slots = List.of(
                openSlot(1L, "12:50", "13:00"),   // 시작이 이용 시작 전
                openSlot(2L, "17:55", "18:05"));  // 종료가 이용 종료 후

        //when, then
        assertEquals(ExclusionReason.OUT_OF_TIME_RANGE, onlyReason(selectOne(a, slots, NOW)));
    }

    @Test
    @DisplayName("슬롯이 하나도 없으면 OUT_OF_TIME_RANGE")
    void select_NoSlots_Excluded() {
        //given
        AttractionView a = operating(1L, "A", 5);

        //when
        CandidateSelection selection = candidateSelector.select(List.of(a), Map.of(), STRAT_AT, END_AT, NOW);

        //then
        assertEquals(ExclusionReason.OUT_OF_TIME_RANGE, onlyReason(selection));
    }

    @Test
    @DisplayName("운영 중단,취소 슬롯만 있으면 SLOT_NOT_OPEN")
    void select_SuspendedOrCancelled_Excluded() {
        //given
        AttractionView a = operating(1L,"A",5);//왜 operating으로 하는가 운영 중단이랑 취소인데?
        List<SlotView> slots = List.of(
                slot(1L, "14:00", "14:10", SlotState.SUSPENDED, 20, 0),
                slot(2L, "15:00", "15:10", SlotState.CANCELLED, 20, 0));

        //when,then
        assertEquals(ExclusionReason.SLOT_NOT_OPEN, onlyReason(selectOne(a, slots, NOW)));
    }

    @Test
    @DisplayName("CLOSED 상태이거나 잔여 정원이 0인 슬롯만 있으면 SLOT_FULL")
    void select_FULL_Excluded() {
        //given
        AttractionView a = operating(1L,"A",5);
        List<SlotView> slots = List.of(
                slot(1L, "14:00", "14:10", SlotState.CLOSED, 20, 20),
                slot(2L, "15:00", "15:10", SlotState.OPEN, 20, 20));

        //when,then
        assertEquals(ExclusionReason.SLOT_FULL, onlyReason(selectOne(a, slots, NOW)));
    }

    @Test
    @DisplayName("예약 마감(시작 30분 전)이 지난 슬롯만 있으면 RESERVATION_CLOSED")
    void select_DeadLinePassed_Excluded() {
        //given
        AttractionView a = operating(1L,"A",5);
        List<SlotView> slots = List.of(openSlot(1L, "13:59", "14:09"));

        //when ,then
        assertEquals(ExclusionReason.RESERVATION_CLOSED, onlyReason(selectOne(a, slots, at("13:30"))));
    }

    @Test
    @DisplayName("현재 시각이 전확히 예약 마감 시각이면 후보에 포함한다(정각 허용)")
    void select_ExactlyAtDeadline_Excluded() {
        //given
        AttractionView a = operating(1L,"A",5);
        List<SlotView> slots = List.of(openSlot(1L, "14:00", "14:10"));

        //when
        CandidateSelection selection = selectOne(a, slots, at("13:30"));

        //then
        assertEquals(1, selection.candidates().size());
    }

    @Test
    @DisplayName("사유는 마지막 남은 슬롯을 걸러낸 필터 기준이다")
    void select_ReasonIsLastFilter() {
        //given
        AttractionView a = operating(1L,"A",5);
        List<SlotView> slots = List.of(
                openSlot(1L, "12:00", "12:10"), //시간 범위에서 탈락
                slot(2L, "14:00", "14:10", SlotState.OPEN, 10, 10)); //매진으로 탈락

        //when,then
        assertEquals(ExclusionReason.SLOT_FULL, onlyReason(selectOne(a, slots, NOW)));
    }
}
