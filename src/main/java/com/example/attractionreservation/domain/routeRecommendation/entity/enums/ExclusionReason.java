package com.example.attractionreservation.domain.routeRecommendation.entity.enums;

import lombok.Getter;

@Getter
//추천에서 제외된 이유
public enum ExclusionReason {
    NOT_OPERATING("현재 운영하지 않는 어트랙션입니다."),
    OUT_OF_TIME_RANGE("선택한 이용 시간 안에 운영하는 슬롯이 없습니다."),
    SLOT_NOT_OPEN("예약 가능한 상태의 슬롯이 없습니다."),
    SLOT_FULL("예약 가능한 슬롯이 모두 매진되었습니다."),
    RESERVATION_CLOSED("예약 마감 시각(탑승 30분 전)이 지난 슬롯만 남아 있습니다."),
    TIME_CONFLICT("선택한 이용 시간 내에 방문하기 어려워 추천에서 제외되었습니다.");

    private final String message;

    ExclusionReason(String message) {
        this.message = message;
    }

}
