package com.example.attractionreservation.domain.attractionSlot.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;


@Getter
@RequiredArgsConstructor
// 사용자 슬롯 조회에서 지금 예약할 수 없는 이유
public enum UnavailableReason {

    ATTRACTION_NOT_OPERATING("어트랙션 운영 안 함"),
    SUSPENDED("운영 중단"),
    CLOSED("예약 마감"),
    DEADLINE_PASSED("예약 마감 시각 지남"),
    FULL("잔여 정원 없음");

    private final String description;
}
