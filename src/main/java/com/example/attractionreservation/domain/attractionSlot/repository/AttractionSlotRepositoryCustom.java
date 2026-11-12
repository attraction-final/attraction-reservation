package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;

import java.time.LocalDateTime;
import java.util.List;

public interface AttractionSlotRepositoryCustom {

    // 시간이 겹치는 슬롯 개수 (등록용)
    long countOverlappingSlots(Long attractionId, LocalDateTime startsAt, LocalDateTime endsAt);

    // 시간이 겹치는 슬롯 개수 (시간 수정용, 자기 자신은 제외)
    long countOverlappingSlotsExcludingSlotId(Long attractionId, Long excludeSlotId,
                                              LocalDateTime startsAt, LocalDateTime endsAt);

    // 특정 어트랙션의 하루치 슬롯 (시작 시각 빠른 순)
    List<AttractionSlot> findSlotsByAttractionIdAndDate(Long attractionId, LocalDateTime dayStart,
                                                        LocalDateTime nextDayStart, boolean includeCancelled);

    // 여러 어트랙션의 "오늘 예약 가능한 슬롯"을 한 번에 조회 (N+1 방지)
    // 어트랙션 목록 조회의 nextSlot 에 사용
    List<ReservableSlotView> findReservableSlots(List<Long> attractionIds,
                                                 LocalDateTime reservableFrom,
                                                 LocalDateTime todayEnd);
}
