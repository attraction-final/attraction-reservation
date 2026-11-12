package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionSlotService.class,
        AttractionSlotCapacityQueryIntegrationTest.MySqlConfig.class})
@Execution(ExecutionMode.SAME_THREAD)
class AttractionSlotCapacityQueryIntegrationTest {

    private static final int CAPACITY = 10;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 11, 10, 16, 0);

    @Autowired
    private AttractionSlotService attractionSlotService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Statistics statistics;
    private Fixture fixture;

    @TestConfiguration(proxyBeanMethods = false)
    static class MySqlConfig {
        @Bean
        @ServiceConnection
        MySQLContainer mysqlContainer() {
            // 부하 환경과 같은 MySQL 8.4에서 정원 확보의 SQL 수를 확인한다.
            return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
        }
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            transactionTemplate.executeWithoutResult(status -> {
                entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
                entityManager.flush();
                entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
            });
        }
    }

    @ParameterizedTest
    @CsvSource({"0, OPEN, false", "8, OPEN, false", "9, CLOSED, true"})
    @DisplayName("이미 잠근 슬롯은 추가 SQL 한 번 이내로 정원을 확보하고 마지막 자리는 자동 마감한다")
    void acquireCapacityWithinSingleStatementAfterLock(
            int previousCount, SlotStatus expectedStatus, boolean expectedAutoClosed) {
        // given
        seedSlot(previousCount, SlotStatus.OPEN);

        // when
        long statementCount = Objects.requireNonNull(transactionTemplate.execute(status -> {
            // 예약 생성과 동일하게 같은 트랜잭션에서 먼저 잠금 조회한다.
            attractionSlotService.findSlot(fixture.slotId());
            // fixture 생성·잠금 SELECT는 예산에서 제외하고 정원 확보와 flush만 측정한다.
            statistics.clear();
            attractionSlotService.tryReservationCapacity(fixture.slotId());
            entityManager.flush();
            return statistics.getPrepareStatementCount();
        }));

        // then
        Snapshot saved = readSnapshot();
        assertThat(saved.reservedCount()).isEqualTo(previousCount + 1);
        assertThat(saved.remainingCount()).isEqualTo(CAPACITY - previousCount - 1);
        assertThat(saved.status()).isEqualTo(expectedStatus);
        assertThat(saved.autoClosed()).isEqualTo(expectedAutoClosed);
        System.out.printf("KAN-155 capacity SQL: previousCount=%d statements=%d%n", previousCount, statementCount);
        // 개선 목표: 잠금을 이미 보유한 경로에서 UPDATE 외 재조회 SQL을 줄인다.
        // 현재 bulk UPDATE + refresh 구현은 이 예산을 초과할 수 있다.
        assertThat(statementCount).as("잠금 조회 이후 정원 확보와 flush의 SQL 수").isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("예약 불가 상태에서는 인원과 상태를 변경하지 않고 자리 확보 오류를 반환한다")
    void rejectCapacityAcquisitionForUnavailableStatus(SlotStatus slotStatus) {
        // given
        seedSlot(3, slotStatus);

        // when & then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            attractionSlotService.findSlot(fixture.slotId());
            attractionSlotService.tryReservationCapacity(fixture.slotId());
        })).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED));
        Snapshot saved = readSnapshot();
        assertThat(saved.reservedCount()).isEqualTo(3);
        assertThat(saved.status()).isEqualTo(slotStatus);
        assertThat(saved.autoClosed()).isFalse();
    }

    @Test
    @DisplayName("OPEN이라도 정원이 가득 차면 예약 인원을 초과시키지 않는다")
    void rejectAcquisitionWhenCapacityIsFull() {
        // given
        seedSlot(CAPACITY, SlotStatus.OPEN);

        // when & then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            attractionSlotService.findSlot(fixture.slotId());
            attractionSlotService.tryReservationCapacity(fixture.slotId());
        })).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED));
        Snapshot saved = readSnapshot();
        assertThat(saved.reservedCount()).isEqualTo(CAPACITY);
        assertThat(saved.remainingCount()).isZero();
        assertThat(saved.status()).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    @DisplayName("마지막 자리 확보 후 롤백하면 인원 증가와 자동 마감을 모두 되돌린다")
    void rollBackCapacityAndAutomaticClosureTogether() {
        // given
        seedSlot(CAPACITY - 1, SlotStatus.OPEN);

        // when
        transactionTemplate.executeWithoutResult(status -> {
            attractionSlotService.findSlot(fixture.slotId());
            attractionSlotService.tryReservationCapacity(fixture.slotId());
            entityManager.flush();
            AttractionSlot changed = entityManager.find(AttractionSlot.class, fixture.slotId());
            assertThat(changed.getReservedCount()).isEqualTo(CAPACITY);
            assertThat(changed.getStatus()).isEqualTo(SlotStatus.CLOSED);
            assertThat(changed.isAutoClosed()).isTrue();
            status.setRollbackOnly();
        });

        // then
        Snapshot saved = readSnapshot();
        assertThat(saved.reservedCount()).isEqualTo(CAPACITY - 1);
        assertThat(saved.remainingCount()).isEqualTo(1);
        assertThat(saved.status()).isEqualTo(SlotStatus.OPEN);
        assertThat(saved.autoClosed()).isFalse();
    }

    @Test
    @DisplayName("트랜잭션 없이 정원 확보를 호출하면 새 트랜잭션을 만들지 않고 거부한다")
    void rejectCapacityAcquisitionWithoutTransaction() {
        // given
        seedSlot(0, SlotStatus.OPEN);

        // when & then
        assertThatThrownBy(() -> attractionSlotService.tryReservationCapacity(fixture.slotId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(readSnapshot().reservedCount()).isZero();
    }

    @Test
    @DisplayName("실제 트랜잭션이 있어도 슬롯 잠금 없이 인원을 변경할 수 없다")
    void rejectCapacityAcquisitionWithoutPessimisticLock() {
        // given
        seedSlot(0, SlotStatus.OPEN);

        // when & then
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                attractionSlotService.tryReservationCapacity(fixture.slotId())))
                .isInstanceOf(IllegalStateException.class);
        Snapshot saved = readSnapshot();
        assertThat(saved.reservedCount()).isZero();
        assertThat(saved.status()).isEqualTo(SlotStatus.OPEN);
        assertThat(saved.autoClosed()).isFalse();
    }

    private void seedSlot(int reservedCount, SlotStatus status) {
        fixture = Objects.requireNonNull(transactionTemplate.execute(transaction -> {
            Attraction attraction = Attraction.create(
                    "KAN155-" + UUID.randomUUID(), "정원 확보 SQL 검증", "A구역", 10, 5);
            entityManager.persist(attraction);
            AttractionSlot slot = AttractionSlot.create(attraction, STARTS_AT, CAPACITY);
            // 기존 예약 인원이 있는 슬롯을 준비하며, 예약 생성 API 전체의 정합성 검증은 아니다.
            ReflectionTestUtils.setField(slot, "reservedCount", reservedCount);
            if (status != SlotStatus.OPEN) {
                slot.changeStatus(status);
            }
            entityManager.persist(slot);
            entityManager.flush();
            return new Fixture(attraction.getId(), slot.getId());
        }));
    }

    private Snapshot readSnapshot() {
        // 서비스 트랜잭션이 끝난 뒤 새 영속성 컨텍스트에서 실제 저장 결과를 읽는다.
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            return new Snapshot(slot.getReservedCount(), slot.getRemainingCount(), slot.getStatus(), slot.isAutoClosed());
        }));
    }

    private record Fixture(Long attractionId, Long slotId) {}

    private record Snapshot(int reservedCount, int remainingCount, SlotStatus status, boolean autoClosed) {}
}
