package com.example.attractionreservation.domain.routeRecommendation.algorithm;
//추천 계산에서 보는 슬롯 상태
public enum SlotState {
    OPEN,
    CLOSED,
    SUSPENDED,
    CANCELLED //소프트삭제
}
