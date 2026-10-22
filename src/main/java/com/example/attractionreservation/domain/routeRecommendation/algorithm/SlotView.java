package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.time.LocalDateTime;

// 추천 계산에 필요한 슬롯 정보만 담은 스냅샷
public record SlotView(
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        SlotState state,
        int capacity,
        int reservesCount
) {
    public int remainingCapacity() {
        return capacity - reservesCount;
    }
}
