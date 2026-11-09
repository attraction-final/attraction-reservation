package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.domain.routeRecommendation.algorithm.TravelTimeTable.PathTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TravelTimeTableTest {

    private static final int FALLBACK = 99;

    @Test
    @DisplayName("방향별 실측 시간을 그대로 반환한다 (A→B와 B→A는 다를 수 있다)")
    void minutesBetween_DifferentByDirection() {
        TravelTimeTable table = TravelTimeTable.from(List.of(
                new PathTime(1L, 2L, 5, true),
                new PathTime(2L, 1L, 7, true)));

        assertThat(table.minutesBetween(1L, 2L, FALLBACK)).isEqualTo(5);
        assertThat(table.minutesBetween(2L, 1L, FALLBACK)).isEqualTo(7);
    }

    @Test
    @DisplayName("정문에서 출발하는 첫 이동(from = null)은 대체값을 반환한다")
    void minutesBetween_FromEntrance_ReturnsFallback() {
        TravelTimeTable table = TravelTimeTable.from(List.of(new PathTime(1L, 2L, 5, true)));

        assertThat(table.minutesBetween(null, 2L, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("구간 데이터가 없으면 대체값을 반환한다")
    void minutesBetween_NoPath_ReturnsFallback() {
        TravelTimeTable table = TravelTimeTable.from(List.of(new PathTime(1L, 2L, 5, true)));

        assertThat(table.minutesBetween(2L, 1L, FALLBACK)).isEqualTo(FALLBACK); // 역방향 없음
        assertThat(table.minutesBetween(1L, 3L, FALLBACK)).isEqualTo(FALLBACK); // 도착지 없음
        assertThat(TravelTimeTable.empty().minutesBetween(1L, 2L, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("비활성 구간은 실측값이 있어도 대체값을 반환한다")
    void minutesBetween_InactivePath_ReturnsFallback() {
        TravelTimeTable table = TravelTimeTable.from(List.of(new PathTime(1L, 2L, 5, false)));

        assertThat(table.minutesBetween(1L, 2L, FALLBACK)).isEqualTo(FALLBACK);
    }
}
