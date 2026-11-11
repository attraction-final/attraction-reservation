package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class RouteRecommendationRepositoryTest {

    @Autowired
    private RouteRecommendationRepository routeRecommendationRepository;

    @Autowired
    private EntityManager entityManager;

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }

    private Long saveRecommendation() {
        // 순서를 섞어 넘겨 저장 후 조회 시 sequenceNumber 순으로 나오는지 확인한다
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L, 5001L, at("13:00"), at("18:00"),
                List.of(
                        RouteRecommendationStop.of(3, 103L, 9301L, "아마존 익스프레스", 12, false, at("14:52"), at("15:30"), at("15:40")),
                        RouteRecommendationStop.of(1, 102L, 9201L, "사파리", 10, true, at("13:10"), at("13:30"), at("13:50")),
                        RouteRecommendationStop.of(2, 101L, 9001L, "T익스프레스", 15, false, at("14:05"), at("14:30"), at("14:40"))),
                List.of(RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.LOCATION_NOT_FOUND)));
        Long id = routeRecommendationRepository.save(recommendation).getId();

        entityManager.flush();
        entityManager.clear();
        return id;
    }

    @Test
    @DisplayName("저장 후 상세 조회하면 방문지가 방문 순서대로 나오고 제외·추정 여부도 유지된다")
    void findDetailById_Saved_ReturnsStopsInSequence() {
        // given
        Long id = saveRecommendation();

        // when
        Optional<RouteRecommendation> found = routeRecommendationRepository.findDetailById(id);

        // then
        assertThat(found).isPresent();
        RouteRecommendation recommendation = found.get();
        assertThat(recommendation.getStops())
                .extracting(RouteRecommendationStop::getSequenceNumber)
                .containsExactly(1, 2, 3);
        assertThat(recommendation.getStops())
                .extracting(RouteRecommendationStop::isMoveEstimated)
                .containsExactly(true, false, false);
        assertThat(recommendation.getExclusions())
                .extracting(RouteRecommendationExclusion::getReasonCode)
                .containsExactly(ExclusionReason.LOCATION_NOT_FOUND);
        assertThat(recommendation.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("없는 추천 ID로 상세 조회하면 빈 결과를 반환한다")
    void findDetailById_NotExists_Empty() {
        // when
        Optional<RouteRecommendation> found = routeRecommendationRepository.findDetailById(999L);

        // then
        assertThat(found).isEmpty();
    }
}