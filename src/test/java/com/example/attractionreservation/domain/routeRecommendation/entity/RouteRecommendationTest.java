package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


public class RouteRecommendationTest {

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }


    private static RouteRecommendationStop safari() {
        return RouteRecommendationStop.of(1, 102L, 9201L, "사파리", 10, at("13:10"), at("13:30"), at("13:50"));
    }

    private static RouteRecommendationStop tExpress() {
        return RouteRecommendationStop.of(2, 101L, 9001L, "T익스프레스", 15, at("14:05"), at("14:30"), at("14:40"));
    }

    private static RouteRecommendationStop amazon() {
        return RouteRecommendationStop.of(3, 103L, 9301L, "아마존 익스프레스", 12, at("14:52"), at("15:30"), at("15:40"));
    }

    @Test
    @DisplayName("제외된 어트랙션이 있으면 PARTIAL 이고 합계와 종료 예상 시각을 방문지로 계산한다")
    void create_WithExclusions_PartialAndSummarized() {
        // given
        List<RouteRecommendationExclusion> exclusions = List.of(
                RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.SLOT_FULL),
                RouteRecommendationExclusion.of(105L, "어트랙션 E", ExclusionReason.TIME_CONFLICT));

        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari(), tExpress(), amazon()), exclusions);

        // then
        assertThat(recommendation.getResultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(recommendation.getTotalSelectedAttractions()).isEqualTo(5);
        // assertThat(실제값).isEqualTo(예상값). 10+15+12=37.
        // 대기=20+25+38=83, 탑승=20+10+10=40, 13:00~15:40=160분.
        assertThat(recommendation.getTotalMoveMinutes()).isEqualTo(37);
        assertThat(recommendation.getTotalWaitMinutes()).isEqualTo(83);
        assertThat(recommendation.getTotalRideMinutes()).isEqualTo(40);
        assertThat(recommendation.getTotalDurationMinutes()).isEqualTo(160);
        assertThat(recommendation.getEstimatedEndAt()).isEqualTo(at("15:40"));
    }

    @Test
    @DisplayName("총 소요 시간은 이동 + 대기 + 탑승 합계와 같다")
    void create_TotalDuration_EqualsSumOfParts() {
        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari(), tExpress(), amazon()), List.of());

        // then
        assertThat(recommendation.getTotalDurationMinutes()).isEqualTo(
                recommendation.getTotalMoveMinutes()
                        + recommendation.getTotalWaitMinutes()
                        + recommendation.getTotalRideMinutes());
    }

    @Test
    @DisplayName("제외된 어트랙션이 없으면 FULL 이다")
    void create_NoExclusions_Full() {
        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari()), List.of());

        // then
        assertThat(recommendation.getResultType()).isEqualTo(RouteResultType.FULL);
        assertThat(recommendation.getTotalSelectedAttractions()).isEqualTo(1);
    }

    @Test
    @DisplayName("방문지를 순서와 다르게 넘겨도 방문 순서대로 저장하고 부모를 연결한다")
    void create_UnorderedStops_SortedBySequence() {
        // given: 3 → 1 → 2 순서로 넘긴다
        List<RouteRecommendationStop> stops = List.of(amazon(), safari(), tExpress());

        // when
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), stops, List.of());

        // then
        assertThat(recommendation.getStops())
                .extracting(RouteRecommendationStop::getSequenceNumber)
                .containsExactly(1, 2, 3);
        assertThat(recommendation.getStops())
                .allSatisfy(stop -> assertThat(stop.getRouteRecommendation()).isSameAs(recommendation));
        assertThat(recommendation.getEstimatedEndAt()).isEqualTo(at("15:40"));   // 마지막 방문 기준
    }

    @Test
    @DisplayName("방문지가 없으면 추천을 만들 수 없다")
    void create_NoStops_ThrowsException() {
        //given
        assertThatThrownBy(() -> RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("방문지의 대기·탑승 시간은 도착·슬롯 시각으로 계산한다")
    void stopOf_CalculatesWaitAndRide() {
        // when
        RouteRecommendationStop stop = safari();

        // then: 대기 13:30 − 13:10 = 20분, 탑승 13:50 − 13:30 = 20분
        assertThat(stop.getEstimatedWaitMin()).isEqualTo(20);
        assertThat(stop.getEstimatedRideMin()).isEqualTo(20);
    }

    @Test
    @DisplayName("제외 항목은 사유 코드와 당시 안내 문구를 함께 가진다")
    void exclusionOf_StoresReasonMessage() {
        // when
        RouteRecommendationExclusion exclusion =
                RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.SLOT_FULL);

        // then
        assertThat(exclusion.getReasonCode()).isEqualTo(ExclusionReason.SLOT_FULL);
        assertThat(exclusion.getReasonMessage()).isEqualTo(ExclusionReason.SLOT_FULL.getMessage());
    }

    @Test
    @DisplayName("요청한 회원이 추천 소유자인지 확인한다")
    void isOwnedBy_ComparesUserId() {
        // given
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari()), List.of());

        // when & then
        assertThat(recommendation.isOwnedBy(1L)).isTrue();
        assertThat(recommendation.isOwnedBy(2L)).isFalse();
    }

    //삭제 테스트
    @Test
    @DisplayName("삭제하면 전달받은 시각이 삭제 시각으로 기록된다")
    void delete_RecordsDeletedAt() {
        // given
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari()), List.of());
        Instant now = Instant.parse("2026-10-20T05:00:00Z");

        // when
        recommendation.delete(now);

        // then
        assertThat(recommendation.isDeleted()).isTrue();
        assertThat(recommendation.getDeletedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("이미 삭제된 추천을 다시 삭제해도 처음 삭제 시각을 유지한다")
    void delete_AlreadyDeleted_KeepsFirstDeletedAt() {
        // given
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"), List.of(safari()), List.of());
        Instant first = Instant.parse("2026-10-20T05:00:00Z");
        recommendation.delete(first);

        // when
        recommendation.delete(Instant.parse("2026-10-20T06:00:00Z"));

        // then
        assertThat(recommendation.getDeletedAt()).isEqualTo(first);
    }
}
