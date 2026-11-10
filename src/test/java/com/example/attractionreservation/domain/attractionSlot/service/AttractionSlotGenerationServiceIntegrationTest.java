package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 슬롯 자동 생성 대상 조회를 실제 MySQL의 읽기 전용 트랜잭션에서 검증한다.
// H2와 테스트 트랜잭션(읽기·쓰기)에서는 읽기 전용 트랜잭션의 FOR UPDATE 거부가 드러나지 않는다. (KAN-135)
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionSlotGenerationService.class,
        AttractionSlotGenerationServiceIntegrationTest.MySqlConfig.class})
class AttractionSlotGenerationServiceIntegrationTest {

    @Autowired
    private AttractionSlotGenerationService attractionSlotGenerationService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Long activeId;
    private Long deletedId;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            return new MySQLContainer(DockerImageName.parse("mysql:9.6"));
        }
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            Attraction active = Attraction.create(
                    "슬롯생성-" + UUID.randomUUID(), "운영 중", "A구역", 30, 5
            );
            Attraction deleted = Attraction.create(
                    "슬롯생성-" + UUID.randomUUID(), "삭제됨", "B구역", 30, 5
            );
            deleted.deleted();
            entityManager.persist(active);
            entityManager.persist(deleted);
            activeId = active.getId();
            deletedId = deleted.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            for (Long id : new Long[]{activeId, deletedId}) {
                Attraction attraction = id == null ? null : entityManager.find(Attraction.class, id);
                if (attraction != null) {
                    entityManager.remove(attraction);
                }
            }
        });
    }

    @Test
    @DisplayName("읽기 전용 트랜잭션에서 삭제되지 않은 어트랙션 ID만 조회한다")
    void findTargetAttractionIds_ReadOnlyTransaction_ReturnsActiveIds() {
        // given: setUp에서 운영 중 1건, 삭제 1건 저장

        // when
        List<Long> result = attractionSlotGenerationService.findTargetAttractionIds();

        // then
        assertThat(result)
                .contains(activeId)
                .doesNotContain(deletedId);
    }
}
