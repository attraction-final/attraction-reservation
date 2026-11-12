package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * H2(MySQL 모드) 테스트 DB 에서 엔티티 매핑과 조회 쿼리를 검증한다.
 * 여기서 넣는 행은 테스트 DB 에만 들어가며 실제 DB 와 무관하다.
 * (어트랙션 FK 는 엔티티에 매핑하지 않았으므로 attractions 행 없이도 저장된다)
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class AttractionPathRepositoryTest {

    @Autowired
    private AttractionPathRepository attractionPathRepository;

    @Autowired
    private AttractionLocationRepository attractionLocationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("선택한 어트랙션끼리의 구간만, 비활성 구간까지 포함해 한 번에 조회한다")
    void findAllBetween_returnsOnlyPathsAmongSelected_includingInactive() {
        attractionPathRepository.saveAll(List.of(
                AttractionPath.of(1L, 2L, 322, 5, false, true),
                AttractionPath.of(2L, 1L, 322, 5, false, true),
                AttractionPath.of(1L, 3L, 641, 11, true, false),    // 선택 안의 비활성·추정 구간
                AttractionPath.of(1L, 9L, 500, 9, false, true),     // 도착지가 선택 밖
                AttractionPath.of(9L, 2L, 500, 9, false, true)));   // 출발지가 선택 밖
        entityManager.flush();
        entityManager.clear();

        List<AttractionPath> result = attractionPathRepository.findAllBetween(List.of(1L, 2L, 3L));

        assertThat(result)
                .extracting(AttractionPath::getFromAttractionId, AttractionPath::getToAttractionId,
                        AttractionPath::getDistanceM, AttractionPath::getMoveMinutes,
                        AttractionPath::isEstimated, AttractionPath::isActive)
                .containsExactlyInAnyOrder(
                        tuple(1L, 2L, 322, 5, false, true),
                        tuple(2L, 1L, 322, 5, false, true),
                        tuple(1L, 3L, 641, 11, true, false));
    }

    @Test
    @DisplayName("선택한 어트랙션의 좌표만 조회하고, 좌표가 없는 어트랙션은 결과에 없다")
    void findAllByAttractionIdIn_returnsExistingLocations() {
        attractionLocationRepository.saveAll(List.of(
                AttractionLocation.of(1L, 37.2923658, 127.1995663),
                AttractionLocation.of(2L, 37.2918125, 127.2011406),
                AttractionLocation.of(9L, 37.29, 127.2025)));
        entityManager.flush();
        entityManager.clear();

        List<AttractionLocation> result = attractionLocationRepository.findAllByAttractionIdIn(List.of(1L, 2L, 3L));

        assertThat(result)
                .extracting(AttractionLocation::getAttractionId, AttractionLocation::getLatitude,
                        AttractionLocation::getLongitude)
                .containsExactlyInAnyOrder(
                        tuple(1L, 37.2923658, 127.1995663),
                        tuple(2L, 37.2918125, 127.2011406));
    }
}
