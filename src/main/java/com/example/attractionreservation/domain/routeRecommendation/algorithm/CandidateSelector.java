package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

//선택한 어트랙션마다 이용 시간 안에서 예약 가능한 슬롯을 골라 후보와 제외 목록으로 나눈다 
@Component
public class CandidateSelector {

    public static final int RESERVATION_DEADLINE_MINUTES = 30;

    public CandidateSelection select(
            List<AttractionView> attractions,
            Map<Long, List<SlotView>> slotsByAttractionId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime now
    ) {
        List<SlotFilter> slotFilters = slotFilters(startAt, endAt, now);
        List<CandidateAttraction> candidates = new ArrayList<>();
        List<Exclusion> exclusions = new ArrayList<>();

        // 입력 순서대로 판정하므로 후보·제외 목록의 상대 순서도 요청 순서를 유지한다
        for (AttractionView attraction : attractions) {
            List<SlotView> slots = slotsByAttractionId.getOrDefault(attraction.attractionId(), List.of());
            SlotSelection selection = selectSlots(attraction, slots, slotFilters);

            if (selection.isExcluded()) {
                exclusions.add(new Exclusion(attraction.attractionId(), attraction.attractionName(), selection.reason()));
            } else {
                candidates.add(toCandidate(attraction, selection.slots()));
            }
        }
        return new CandidateSelection(candidates, exclusions);
    }

    // 슬롯 필터. 위에서부터 차례로 적용하고, 남은 슬롯이 0개가 된 필터의 사유로 제외한다 
    private static List<SlotFilter> slotFilters(LocalDateTime startAt, LocalDateTime endAt, LocalDateTime now) {
        return List.of(
                new SlotFilter(slot -> isWithinTimeRange(slot, startAt, endAt), ExclusionReason.OUT_OF_TIME_RANGE),
                new SlotFilter(CandidateSelector::isNotStopped, ExclusionReason.SLOT_NOT_OPEN),
                new SlotFilter(CandidateSelector::hasRemainingCapacity, ExclusionReason.SLOT_FULL),
                new SlotFilter(slot -> isBeforeReservationDeadline(slot, now), ExclusionReason.RESERVATION_CLOSED)
        );
    }

    // 어트랙션 하나를 판정한다. 제외되면 그 사유를, 아니면 남은 슬롯을 돌려준다 
    private static SlotSelection selectSlots(AttractionView attraction, List<SlotView> slots, List<SlotFilter> slotFilters) {
        if (!attraction.operating()) {
            return SlotSelection.excluded(ExclusionReason.NOT_OPERATING);
        }
        List<SlotView> remaining = slots;
        for (SlotFilter slotFilter : slotFilters) {
            remaining = filter(remaining, slotFilter.condition());
            if (remaining.isEmpty()) {
                return SlotSelection.excluded(slotFilter.reason());
            }
        }
        return SlotSelection.selected(remaining);
    }

    private static List<SlotView> filter(List<SlotView> slots, Predicate<SlotView> condition) {
        return slots.stream().filter(condition).toList();
    }

    // 슬롯이 선택한 이용 시간 안에 완전히 들어오는지 (경계 포함)
    private static boolean isWithinTimeRange(SlotView slot, LocalDateTime startAt, LocalDateTime endAt) {
        return !slot.startsAt().isBefore(startAt) && !slot.endsAt().isAfter(endAt);
    }

    private static boolean isNotStopped(SlotView slot) {
        return slot.state() != SlotState.SUSPENDED && slot.state() != SlotState.CANCELLED;
    }

    private static boolean hasRemainingCapacity(SlotView slot) {
        return slot.state() != SlotState.CLOSED && slot.remainingCapacity() >= 1;
    }

    // 예약 마감은 시작 30분 전. 마감 시각 정각까지는 허용한다
    private static boolean isBeforeReservationDeadline(SlotView slot, LocalDateTime now) {
        return !now.isAfter(slot.startsAt().minusMinutes(RESERVATION_DEADLINE_MINUTES));
    }

    private static CandidateAttraction toCandidate(AttractionView attraction, List<SlotView> slots) {
        List<CandidateSlot> sortedSlots = slots.stream()
                .sorted(Comparator.comparing(SlotView::startsAt))
                .map(slot -> new CandidateSlot(slot.slotId(), slot.startsAt(), slot.endsAt()))
                .toList();
        return new CandidateAttraction(
                attraction.attractionId(), attraction.attractionName(), attraction.moveMinutes(), sortedSlots);
    }

    // 필터 조건과, 이 조건에서 슬롯이 모두 걸러졌을 때의 제외 사유 
    private record SlotFilter(Predicate<SlotView> condition, ExclusionReason reason) {
    }

    // 어트랙션 하나의 판정 결과. reason 이 있으면 제외, 없으면 slots 가 후보 슬롯 
    private record SlotSelection(List<SlotView> slots, ExclusionReason reason) {

        static SlotSelection excluded(ExclusionReason reason) {
            return new SlotSelection(List.of(), reason);
        }

        static SlotSelection selected(List<SlotView> slots) {
            return new SlotSelection(slots, null);
        }

        boolean isExcluded() {
            return reason != null;
        }
    }
}
