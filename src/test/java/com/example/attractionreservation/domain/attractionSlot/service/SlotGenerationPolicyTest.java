package com.example.attractionreservation.domain.attractionSlot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotGenerationPolicyTest {

    // 정상 설정값. 각 테스트는 이 중 하나만 바꿔서 "그 값 때문에" 실패하는지 본다.
    private static final int DAYS = 7;
    private static final LocalTime FIRST = LocalTime.of(10, 0);
    private static final LocalTime LAST = LocalTime.of(21, 0);
    private static final int INTERVAL = 30;
    private static final int CAPACITY = 20;

    @Test
    @DisplayName("올바른 설정이면 값이 그대로 담긴다")
    void validPolicy() {
        SlotGenerationPolicy policy = new SlotGenerationPolicy(DAYS, FIRST, LAST, INTERVAL, CAPACITY);

        assertThat(policy.daysAhead()).isEqualTo(DAYS);
        assertThat(policy.firstStartTime()).isEqualTo(FIRST);
        assertThat(policy.lastStartTime()).isEqualTo(LAST);
        assertThat(policy.intervalMinutes()).isEqualTo(INTERVAL);
        assertThat(policy.capacity()).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("경계값(1일·365일, 첫=마지막 시각, 21:59)은 허용된다")
    void boundaryValuesAreAllowed() {
        new SlotGenerationPolicy(1, FIRST, LAST, 1, 1);
        new SlotGenerationPolicy(365, FIRST, LAST, INTERVAL, CAPACITY);
        new SlotGenerationPolicy(DAYS, LocalTime.of(12, 0), LocalTime.of(12, 0), INTERVAL, CAPACITY);
        new SlotGenerationPolicy(DAYS, FIRST, LocalTime.of(21, 59), INTERVAL, CAPACITY);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 366})
    @DisplayName("daysAhead 가 1~365 밖이면 실패한다")
    void invalidDaysAhead(int days) {
        assertThatThrownBy(() -> new SlotGenerationPolicy(days, FIRST, LAST, INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daysAhead");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("intervalMinutes 가 1 미만이면 실패한다")
    void invalidInterval(int interval) {
        assertThatThrownBy(() -> new SlotGenerationPolicy(DAYS, FIRST, LAST, interval, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intervalMinutes");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("capacity 가 1 미만이면 실패한다")
    void invalidCapacity(int capacity) {
        assertThatThrownBy(() -> new SlotGenerationPolicy(DAYS, FIRST, LAST, INTERVAL, capacity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("firstStartTime 이 없으면 실패한다")
    void nullFirstStartTime() {
        assertThatThrownBy(() -> new SlotGenerationPolicy(DAYS, null, LAST, INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("반드시 설정");
    }

    @Test
    @DisplayName("lastStartTime 이 없으면 실패한다")
    void nullLastStartTime() {
        assertThatThrownBy(() -> new SlotGenerationPolicy(DAYS, FIRST, null, INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("반드시 설정");
    }

    @Test
    @DisplayName("firstStartTime 이 lastStartTime 보다 늦으면 실패한다")
    void firstAfterLast() {
        assertThatThrownBy(() -> new SlotGenerationPolicy(
                DAYS, LocalTime.of(15, 0), LocalTime.of(14, 0), INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("늦을 수 없습니다");
    }

    @Test
    @DisplayName("firstStartTime 이 개장(10:00) 전이면 실패한다")
    void firstBeforeOpen() {
        assertThatThrownBy(() -> new SlotGenerationPolicy(
                DAYS, LocalTime.of(9, 59), LAST, INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("개장 시각");
    }

    @ParameterizedTest
    @ValueSource(strings = {"22:00", "23:00"})
    @DisplayName("lastStartTime 이 폐장(22:00) 이상이면 실패한다")
    void lastAtOrAfterClose(String last) {
        assertThatThrownBy(() -> new SlotGenerationPolicy(
                DAYS, FIRST, LocalTime.parse(last), INTERVAL, CAPACITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("폐장 시각");
    }
}
