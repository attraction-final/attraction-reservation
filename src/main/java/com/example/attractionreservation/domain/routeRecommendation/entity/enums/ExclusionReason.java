package com.example.attractionreservation.domain.routeRecommendation.entity.enums;

import lombok.Getter;

@Getter
//선택한 어트랙션이 추천 경로에서 제외된 이유
public enum ExclusionReason {
    NOT_OPERATING("현재 운영하지 않는 어트랙션입니다."),
    OUT_OF_TIME_RANGE("지금부터 폐장 전까지 운영하는 슬롯이 없습니다."),
    SLOT_NOT_OPEN("예약 가능한 상태의 슬롯이 없습니다."),
    SLOT_FULL("예약 가능한 슬롯이 모두 매진되었습니다."),
    RESERVATION_CLOSED("예약 마감 시각(탑승 30분 전)이 지난 슬롯만 남아 있습니다."),
    SLOT_MANUALLY_CLOSED("관리자에 의해 예약이 마감된 슬롯만 남아 있습니다."),
    LOCATION_NOT_FOUND("어트랙션 위치 정보가 없어 이동 시간을 계산할 수 없습니다."),
    TIME_CONFLICT("예약 가능한 슬롯은 있지만 이동·대기 시간을 고려하면 이용 시간 안에 방문할 수 없습니다.");

    private final String message;

    ExclusionReason(String message) {
        this.message = message;
    }

}