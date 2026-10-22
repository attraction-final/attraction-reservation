package com.example.attractionreservation.domain.attraction.entity;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum OperationStatus {

    OPERATING("운영중"),
    MAINTENANCE("점검"),
    SUSPENDED("일시중단"),
    DELETED("운영 종료");

    private final String description; // 각 Enum 상수의 설명을 저장하는 필드

    public static OperationStatus from(String value) {
        return Arrays.stream(values())
                .filter(status -> status != DELETED)
                .filter(status -> status.name().equals(value))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_ATTRACTION_STATUS));

        // Arrays.stream()은 배열을 Stream으로 변환하여 순차적으로 처리.
                // 각 운영 상태의 이름(name())과 전달받은 문자열(value)이 일치하는 상태만 필터링.
                // 필터링된 결과 중 첫 번째 값을 가져온다. 없으면 빈 Optional이 반환.
                // 일치하는 상태가 있으면 해당 Enum을 반환. 없으면 BusinessException을 발생.
    }
}
