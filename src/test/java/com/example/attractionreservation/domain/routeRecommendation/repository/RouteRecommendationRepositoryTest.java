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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
public class RouteRecommendationRepositoryTest {
    @Autowired
    private RouteRecommendationRepository routeRecommendationRepository;

    @Autowired
    private EntityManager entityManager;

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }


    private RouteRecommendation saveRecommendation() {
        RouteRecommendation recommendation = RouteRecommendation.create(
                1L,
                5001L,
                at("13:00"),
                at("18:00"),
                List.of(
                        RouteRecommendationStop.of(1, 102L, 9201L, "사파리", 10, at("13:10"), at("13:30"), at("13:50")),
                        RouteRecommendationStop.of(2, 101L, 9001L, "T익스프레스", 15, at("14:05"), at("14:30"), at("14:40")),
                        RouteRecommendationStop.of(3, 103L, 9301L, "아마존 익스프레스", 12, at("14:52"), at("15:30"), at("15:40"))),
                List.of(RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.SLOT_FULL)));
        RouteRecommendation saved = routeRecommendationRepository.save(recommendation);

        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private RouteRecommendation saveRecommendation(Long userId, int stopCount) {
        List<RouteRecommendationStop> stops = new ArrayList<>();
        for (int sequence = 1; sequence <= stopCount; sequence++) {
            LocalDateTime slotStart = at("13:30").plusMinutes((sequence - 1) * 30L);
            stops.add(RouteRecommendationStop.of(
                    sequence, 100L + sequence, 9000L + sequence, "어트랙션" + sequence,
                    5, slotStart.minusMinutes(10), slotStart, slotStart.plusMinutes(10)));
        }
        return routeRecommendationRepository.save(
                RouteRecommendation.create(userId, 5001L, at("13:00"), at("18:00"), stops, List.of()));
    }

    private static PageRequest newestFirst() {
        return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    // 상세 조회 테스트

    @Test
    @DisplayName("상세 조회는 방문지를 방문 순서대로 함께 가져오고 제외 목록도 조회된다")
    void findDetailById_Saved_ReturnsStopsInOrderWithExclusions() {
        // given
        Long id = saveRecommendation().getId();

        // when
        Optional<RouteRecommendation> found = routeRecommendationRepository.findDetailById(id);

        // then
        assertThat(found).isPresent();
        RouteRecommendation recommendation = found.get();
        assertThat(recommendation.getStops())
                .extracting(RouteRecommendationStop::getAttractionId)
                .containsExactly(102L, 101L, 103L);
        assertThat(recommendation.getExclusions())
                .extracting(RouteRecommendationExclusion::getReasonCode)
                .containsExactly(ExclusionReason.SLOT_FULL);
        assertThat(recommendation.getCreatedAt()).isNotNull();   // BaseTimeEntity 감사 시각
    }

    @Test
    @DisplayName("소프트 삭제된 추천은 상세 조회되지 않는다")
    void findDetailById_Deleted_ReturnsEmpty() {
        // given:
        RouteRecommendation saved = saveRecommendation();
        RouteRecommendation recommendation = routeRecommendationRepository.findById(saved.getId()).orElseThrow();
        ReflectionTestUtils.setField(recommendation, "deletedAt", Instant.parse("2026-10-20T05:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        // when
        Optional<RouteRecommendation> found = routeRecommendationRepository.findDetailById(saved.getId());

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("없는 추천 ID 로 상세 조회하면 빈 결과를 반환한다")
    void findDetailById_NotExists_ReturnsEmpty() {
        // when
        Optional<RouteRecommendation> found = routeRecommendationRepository.findDetailById(999L);

        // then
        assertThat(found).isEmpty();
    }


    //목록 조회 테스트
    @Test
    @DisplayName("목록 조회는 본인 추천만 최신순으로 반환한다")
    void findAllByUserIdAndDeletedAtIsNull_OwnRecommendations_NewestFirst() {
        // given
        Long first = saveRecommendation(1L, 1).getId();
        Long second = saveRecommendation(1L, 1).getId();
        saveRecommendation(2L, 1);
        entityManager.flush();
        entityManager.clear();

        // when
        Page<RouteRecommendation> page =
                routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(1L, newestFirst());

        // then
        assertThat(page.getContent())
                .extracting(RouteRecommendation::getId)
                .containsExactly(second, first);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("소프트 삭제된 추천은 목록에서 제외된다")
    void findAllByUserIdAndDeletedAtIsNull_Deleted_Excluded() {
        // given
        Long deletedId = saveRecommendation(1L, 1).getId();
        Long aliveId = saveRecommendation(1L, 1).getId();
        entityManager.flush();
        entityManager.clear();

        RouteRecommendation deleted = routeRecommendationRepository.findById(deletedId).orElseThrow();
        ReflectionTestUtils.setField(deleted, "deletedAt", Instant.parse("2026-10-20T05:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        // when
        Page<RouteRecommendation> page =
                routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(1L, newestFirst());

        // then
        assertThat(page.getContent())
                .extracting(RouteRecommendation::getId)
                .containsExactly(aliveId);
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("추천별 방문지 개수를 한 번에 집계한다")
    void countStopsByRecommendationIds_ReturnsStopCountPerRecommendation() {
        // given: 방문지 3개짜리, 1개짜리
        Long threeStops = saveRecommendation(1L, 3).getId();
        Long oneStop = saveRecommendation(1L, 1).getId();
        entityManager.flush();
        entityManager.clear();

        // when
        List<StopCount> counts =
                routeRecommendationRepository.countStopsByRecommendationIds(List.of(threeStops, oneStop));

        // then
        assertThat(counts).containsExactlyInAnyOrder(
                new StopCount(threeStops, 3L),
                new StopCount(oneStop, 1L));
    }
}
