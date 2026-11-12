package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteRecommendationTest {

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }

    // 이동 7, 대기 3, 탑승 10 (첫 구간은 추정값->실측값이 없어서)
    private static RouteRecommendationStop first() {
        return RouteRecommendationStop.of(1, 101L, 9001L, "T익스프레스", 7, true, at("10:07"), at("10:10"), at("10:20"));
    }

    // 이동 5, 대기 0, 탑승 15
    private static RouteRecommendationStop second() {
        return RouteRecommendationStop.of(2, 102L, 9201L, "사파리", 5, false, at("10:25"), at("10:25"), at("10:40"));
    }

    @Test
    @DisplayName("제외가 없으면 FULL, 합계는 방문 일정의 합이다")
    void create_NoExclusion_Full() {
        // given
        List<RouteRecommendationStop> stops = List.of(first(), second());

        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("10:00"), at("18:00"), stops, List.of());

        // then
        assertThat(recommendation.getResultType()).isEqualTo(RouteResultType.FULL);
        assertThat(recommendation.getTotalSelectedAttractions()).isEqualTo(2);
        assertThat(recommendation.getTotalMoveMinutes()).isEqualTo(12);
        assertThat(recommendation.getTotalWaitMinutes()).isEqualTo(3);
        assertThat(recommendation.getTotalRideMinutes()).isEqualTo(25);
        assertThat(recommendation.getTotalDurationMinutes()).isEqualTo(40);
        assertThat(recommendation.getEstimatedEndAt()).isEqualTo(at("10:40"));
    }

    @Test
    @DisplayName("제외가 하나라도 있으면 PARTIAL이고 선택 수는 방문 + 제외다")
    void create_WithExclusion_Partial() {
        // given
        List<RouteRecommendationExclusion> exclusions = List.of(
                RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.LOCATION_NOT_FOUND));

        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("10:00"), at("18:00"), List.of(first(), second()), exclusions);

        // then
        assertThat(recommendation.getResultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(recommendation.getTotalSelectedAttractions()).isEqualTo(3);
    }

    @Test
    @DisplayName("방문지를 순서와 다르게 넘겨도 방문 순서대로 저장하고 부모를 연결한다")
    void create_UnorderedStops_SortedBySequence() {
        // given
        List<RouteRecommendationStop> stops = List.of(second(), first());

        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("10:00"), at("18:00"), stops, List.of());

        // then
        assertThat(recommendation.getStops())
                .extracting(RouteRecommendationStop::getSequenceNumber)
                .containsExactly(1, 2);
        assertThat(recommendation.getStops())
                .allSatisfy(stop -> assertThat(stop.getRouteRecommendation()).isSameAs(recommendation));
    }

    @Test
    @DisplayName("방문지가 없으면 추천을 만들 수 없다")
    void create_NoStops_ThrowsException() {
        // when & then
        assertThatThrownBy(() -> RouteRecommendation.create(
                1L, 5001L, at("10:00"), at("18:00"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("첫 방문의 이동은 추정값으로 저장된다")
    void of_FirstLegEstimated_KeepsFlag() {
        // when
        RouteRecommendationStop estimated = first();
        RouteRecommendationStop measured = second();

        // then
        assertThat(estimated.isMoveEstimated()).isTrue();
        assertThat(measured.isMoveEstimated()).isFalse();
    }

    @Test
    @DisplayName("방문지의 대기·탑승 시간은 도착·슬롯 시각으로 계산한다")
    void of_CalculatesWaitAndRide() {
        // when
        RouteRecommendationStop stop = first();

        // then
        assertThat(stop.getEstimatedWaitMin()).isEqualTo(3);
        assertThat(stop.getEstimatedRideMin()).isEqualTo(10);
    }

    @Test
    @DisplayName("제외 항목은 사유 코드와 당시 안내 문구를 함께 가진다")
    void of_ExclusionStoresReasonMessage() {
        // when
        RouteRecommendationExclusion exclusion =
                RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.LOCATION_NOT_FOUND);

        // then
        assertThat(exclusion.getReasonCode()).isEqualTo(ExclusionReason.LOCATION_NOT_FOUND);
        assertThat(exclusion.getReasonMessage()).isEqualTo("어트랙션 위치 정보가 없어 이동 시간을 계산할 수 없습니다.");
    }

    @Test
    @DisplayName("요청한 회원이 추천 소유자인지 확인한다")
    void isOwnedBy_ComparesUserId() {
        // given
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("10:00"), at("18:00"), List.of(first()), List.of());

        // when & then
        assertThat(recommendation.isOwnedBy(1L)).isTrue();
        assertThat(recommendation.isOwnedBy(2L)).isFalse();
    }
}