package com.example.attractionreservation.domain.attraction.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// from()의 세부 규칙은 서비스 테스트가 아니라 enum 자체 테스트에서 확인
public class OperationStatusTest {

    @ParameterizedTest
    @EnumSource(OperationStatus.class)
    @DisplayName("상수 이름과 정확히 같은 문자열이면 해당 상태를 반환한다")
    void from_ValidName_ReturnsStatus(OperationStatus status) {
        // when
        OperationStatus result = OperationStatus.from(status.name());

        // then
        assertThat(result).isEqualTo(status);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"maintenance", "Operating", " OPERATING", "OPERATING ", "BROKEN", "운영중"})
    @DisplayName("상수 이름과 정확히 일치하지 않으면 INVALID_ATTRACTION_STATUS 예외가 발생한다")
    void from_InvalidValue_ThrowsException(String value) {
        // when & then
        assertThatThrownBy(() -> OperationStatus.from(value))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_ATTRACTION_STATUS);
    }
}
