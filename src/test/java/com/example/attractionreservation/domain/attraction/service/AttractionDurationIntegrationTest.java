package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationUpcomingReservationReader;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 실제 Service와 MySQL을 사용해 소요 시간 변경 제한 및 거부 후 DB 상태를 검증한다.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionService.class,
        ReservationUpcomingReservationReader.class, AttractionDurationIntegrationTest.MySqlConfig.class})
class AttractionDurationIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);
    private static final LocalDateTime SLOT_START = NOW.withHour(14);

    @Autowired
    private AttractionService attractionService;

    // Redis 캐시 삭제만 제외하고 DB 검증과 수정 트랜잭션은 실제로 수행한다.
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Fixture fixture;

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
        fixture = Objects.requireNonNull(transactionTemplate.execute(transaction -> {
            Attraction attraction = Attraction.create(
                    "kan125-" + UUID.randomUUID(), "기존 설명", "A구역", 10, 5);
            entityManager.persist(attraction);
            // 예약이 없어도 운영 예정 슬롯 자체가 소요 시간 변경을 막아야 한다.
            AttractionSlot slot = AttractionSlot.create(attraction, SLOT_START, 10);
            entityManager.persist(slot);
            entityManager.flush();
            return new Fixture(attraction.getId(), slot.getId());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(transaction -> {
            entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
            entityManager.flush();
            entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
        });
    }

    @Test
    @DisplayName("예약이 없는 미래 슬롯이 있어도 소요 시간 변경을 거부하고 어트랙션과 기존 슬롯을 유지한다")
    void updateAttraction_futureSlotWithoutReservations_rejectsDurationChange() {
        // given
        State before = readState();
        assertThat(before.reservedCount()).isZero();
        assertThat(before.status()).isEqualTo(SlotStatus.OPEN);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "함께 변경 요청한 설명", null, 20, null);

        // when
        Throwable failure;
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            failure = catchThrowable(() -> attractionService.updateAttraction(
                    ADMIN_ID, fixture.attractionId(), request));
        }

        // then
        // 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 조회해 응답이 아닌 실제 저장 결과를 비교한다.
        State after = readState();
        assertSoftly(softly -> {
            softly.assertThat(failure).as("운영 예정 슬롯이 있으면 소요 시간 변경 거부")
                    .isInstanceOf(BusinessException.class);
            softly.assertThat(after).as("소요 시간·함께 요청한 설명·기존 슬롯 모두 변경되지 않음")
                    .isEqualTo(before);
        });
    }

    private State readState() {
        return Objects.requireNonNull(transactionTemplate.execute(transaction -> {
            Attraction attraction = entityManager.find(Attraction.class, fixture.attractionId());
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            return new State(attraction.getDurationMinutes(), attraction.getDescription(),
                    slot.getStartsAt(), slot.getEndsAt(), slot.getReservedCount(), slot.getStatus());
        }));
    }

    private record Fixture(Long attractionId, Long slotId) {
    }

    private record State(Integer durationMinutes, String description,
                         LocalDateTime startsAt, LocalDateTime endsAt,
                         Integer reservedCount, SlotStatus status) {
    }
}
