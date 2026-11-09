package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
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

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class AttractionLocationRepositoryTest {

    @Autowired
    private AttractionLocationRepository attractionLocationRepository;

    @Autowired
    private EntityManager entityManager;

    private Attraction persistAttraction(String name) {
        Attraction attraction = Attraction.create(name, "설명", "A구역", 5, 10);
        entityManager.persist(attraction);
        return attraction;
    }

    @Test
    @DisplayName("좌표가 있고 삭제되지 않은 어트랙션만 ID 순으로 조회한다")
    void findRecommendableAttractions_OnlyActiveWithLocation() {
        // given
        Attraction hurricane = persistAttraction("허리케인");
        Attraction bumperCar = persistAttraction("범퍼카");
        Attraction noLocation = persistAttraction("좌표 없는 어트랙션");
        Attraction deleted = persistAttraction("삭제된 어트랙션");
        deleted.deleted();

        attractionLocationRepository.saveAll(List.of(
                AttractionLocation.of(hurricane.getId(), 37.29397, 127.20161),
                AttractionLocation.of(bumperCar.getId(), 37.2923658, 127.1995663),
                AttractionLocation.of(deleted.getId(), 37.29, 127.2025)));
        entityManager.flush();
        entityManager.clear();

        // when
        List<RecommendableAttractionResponse> result = attractionLocationRepository.findRecommendableAttractions();

        // then
        assertThat(result).containsExactly(
                new RecommendableAttractionResponse(hurricane.getId(), "허리케인"),
                new RecommendableAttractionResponse(bumperCar.getId(), "범퍼카"));
        assertThat(result).extracting(RecommendableAttractionResponse::attractionId)
                .doesNotContain(noLocation.getId(), deleted.getId());
    }
}
