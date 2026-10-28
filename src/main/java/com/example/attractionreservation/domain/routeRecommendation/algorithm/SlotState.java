package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//추천 계산에서 사용하는 슬롯 상태
//알고리즘은 슬롯 엔티티를 직접 참조하지 않고 상태를 검사
public enum SlotState {
    OPEN, //예약 가능 상태
    CLOSED, //마감 상태
    SUSPENDED, //운영 중단 상태
    CANCELLED //취소된 슬롯 상태
}
