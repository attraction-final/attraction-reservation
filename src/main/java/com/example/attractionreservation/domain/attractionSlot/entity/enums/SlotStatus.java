package com.example.attractionreservation.domain.attractionSlot.entity.enums;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum SlotStatus {

    OPEN("운영 가능"),
    CLOSED("예약 마감"),
    SUSPENDED("운영 중단"),
    CANCELLED("취소"); // 슬롯 삭제 API로만 바뀌는 상태 (소프트 삭제)

    private final String description;

    // 관리자가 "상태 변경 API"로 바꿀 수 있는 값만 허용한다. (CANCELLED는 삭제 API 전용)
    public static SlotStatus from(String value) {
        return Arrays.stream(values())
                .filter(status -> status != CANCELLED)
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SLOT_STATUS));
    }
}
