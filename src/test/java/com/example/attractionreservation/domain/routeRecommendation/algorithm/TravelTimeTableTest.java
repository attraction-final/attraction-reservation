package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TravelTimeTableTest {

    @Test
    @DisplayName("활성 구간은 출발 → 도착 방향 그대로 찾을 수 있다")
    void find_ActiveEdge_Present() {
        // given
        PathEdge edge = new PathEdge(1L, 2L, 300, 5, true);
        TravelTimeTable table = TravelTimeTable.from(List.of(edge));

        // when
        var found = table.find(1L, 2L);

        // then
        assertThat(found).contains(edge);
    }

    @Test
    @DisplayName("비활성(조회표에 넣지 않은) 구간이나 없는 구간은 비어 있다")
    void find_InactiveOrMissing_Empty() {
        // given: 비활성 구간은 호출자가 from 에 넣지 않는다
        TravelTimeTable table = TravelTimeTable.from(List.of(new PathEdge(1L, 2L, 300, 5, false)));

        // when, then
        assertThat(table.find(1L, 3L)).isEmpty();
        assertThat(table.find(3L, 1L)).isEmpty();
        assertThat(TravelTimeTable.empty().find(1L, 2L)).isEmpty();
    }

    @Test
    @DisplayName("B→A 구간만 있으면 A→B 는 역방향 값으로 대신하지 않고 비어 있다")
    void find_ReverseOnly_Empty() {
        // given
        TravelTimeTable table = TravelTimeTable.from(List.of(new PathEdge(2L, 1L, 300, 5, false)));

        // when
        var found = table.find(1L, 2L);

        // then
        assertThat(found).isEmpty();
    }
}