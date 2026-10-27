package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//추천 후보 선정

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
//선택한 어트랙션에서 추천 경로 탐색에 사용할 후보를 선정한다.
@Component
public  class CandidateSelector {

    public  static final int RESERVATION_DEADLINE_MINUTES = 30;

    public CandidateSelection select (
            List<AttractionView> attractions,
            Map<Long ,List<SlotView>> slotsByAttractionId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime now //예약 마감을 판단할 현재 시각
    ) {
        List<CandidateAttraction> candidates = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();

        //입력 순서대로 검사하므로 후보, 제외 목록의 상대 순서도 유지
        for (AttractionView attraction : attractions) {
            //운영중인가 ?
            if (!attraction.operating()) {
                exclusions.add(exclude(attraction, ExclusionReason.NOT_OPERATING));
                continue; //현재 어트랙션 끝나고 다음 어트랙션으로
            }
            // 해당 어트랙션 ID에 대한 매핑이 없으면 빈 목록을 반환한다
            List<SlotView> slots = slotsByAttractionId.getOrDefault(attraction.attractionId(), List.of());
            //이용 시간 안에 시작하고 끝나는 슬롯만
            slots = filter(slots ,slot -> isWithinTimeRange(slot, startAt , endAt));
            if (slots.isEmpty()) {
                exclusions.add(exclude(attraction, ExclusionReason.OUT_OF_TIME_RANGE));
                continue;
            }
            //운영 중단 또는 취소된 슬롯을 제외
            slots = filter(slots,CandidateSelector::isNotStopped);
            if (slots.isEmpty()) {
                exclusions.add(exclude(attraction,ExclusionReason.SLOT_NOT_OPEN));
                continue;
            }
            //CLOSED가 아니면서 잔여 정원이 1 이상인 슬롯만
            slots = filter(slots,CandidateSelector::hasRemainingCapacity);
            if (slots.isEmpty()) {
                exclusions.add(exclude(attraction, ExclusionReason.SLOT_FULL));
                continue;
            }
            //현재 시각이 예약 마감 시각을 지나지 않은 슬롯만
            slots = filter(slots , slot -> isBeforeReservationDeadline(slot, now));
            if (slots.isEmpty()) {
                exclusions.add(exclude(attraction,ExclusionReason.RESERVATION_CLOSED));
                continue;
            }

            candidates.add(toCandidate(attraction, slots));
    }
        return new CandidateSelection(candidates, exclusions);

}
    //이미 결정된 제외 정보를 만듬
    private static Exclusion exclude(AttractionView attraction, ExclusionReason reason) {
        return new Exclusion(attraction.attractionId(), attraction.attractionName(), reason);
    }
    //조건을 만족하는 슬롯만 새 목록으로 반환
    private static  List<SlotView> filter(List<SlotView>slots , Predicate<SlotView> condition) {
        return slots.stream().filter(condition).toList();
    }

    //선정한 후보의 슬롯 시각이 이용가능한 슬롯 시각 사이에 있는지
    private static boolean isWithinTimeRange(SlotView slot, LocalDateTime startAt, LocalDateTime endAt) {
        return !slot.startsAt().isBefore(startAt) && !slot.endsAt().isAfter(endAt);
    }
    //운영 중단·취소 상태가 아닌지 확인
    private static  boolean isNotStopped(SlotView slot) {
        return slot.state() != SlotState.SUSPENDED && slot.state() != SlotState.CANCELLED;
        //중단 그리고 취소 순서로 작성한게 의미가 있는가? 순서를 뒤바꿔도 문제가 없ㅏ나?
    }
    //마감 상태가 아니며 최소 1명의 자리가 남아 있는지 확인
    private static boolean hasRemainingCapacity(SlotView slot) {
        return slot.state() != SlotState.CLOSED && slot.remainingCapacity() >= 1;
    }
    //예약 마감 시각을 지나지 않았는지 확인
    //30분 정각 허용
    private static boolean isBeforeReservationDeadline(SlotView slot, LocalDateTime now) {
        return !now.isAfter(slot.startsAt().minusMinutes(RESERVATION_DEADLINE_MINUTES));
    }
    //필터를 통과한 슬롯을 시작 시각순으로 정렬하고 탐색 모델로 변환
    private static CandidateAttraction toCandidate(AttractionView attraction, List<SlotView> slots) {
        List<CandidateSlot> sortedSlots = slots.stream()
                .sorted(Comparator.comparing(SlotView::startsAt))
                .map(slot -> new CandidateSlot(slot.slotId(), slot.startsAt(),slot.endsAt()))
                .toList();
        return new CandidateAttraction(
                attraction.attractionId() , attraction.attractionName(), attraction.moveMinutes(), sortedSlots);
    }
}