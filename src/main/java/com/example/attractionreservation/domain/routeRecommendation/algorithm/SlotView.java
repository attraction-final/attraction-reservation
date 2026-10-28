package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.time.LocalDateTime;
// 추천 계산에 필요한 슬롯 정보만 담은 스냅샷
public record SlotView(
        Long slotId,
        LocalDateTime startsAt, //슬롯 탑승 시작 시각
        LocalDateTime endsAt,  //슬롯 탑승 종료 시각
        SlotState state, //추천 계산에 사용하는 슬롯 상태
        int capacity, //슬롯의 전체 정원
        int reservesCount //조회 시점에 예약되어 있는 인원
) {
    //조회 시점의 잔여 정원을 계산
    public int remainingCapacity() {
        return capacity - reservesCount;
    }
}
