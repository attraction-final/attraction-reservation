package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.reservation.service.ReservationUpcomingReservationReader;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.doAnswer;

// 실제 MySQL 조회로 두 요청이 모두 중복 검사를 마친 후 저장하도록 제어한다.
// 사전 검사의 결과를 false로 고정하지 않고, 각 Service 트랜잭션에서 직접 조회한다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionService.class,
        ReservationUpcomingReservationReader.class,
        AttractionNameConcurrencyIntegrationTest.MySqlConfig.class})
class AttractionNameConcurrencyIntegrationTest {

    private static final Long ADMIN_ID = 99L;

    @Autowired
    private AttractionService attractionService;

    @MockitoSpyBean
    private AttractionRepository attractionRepository;

    // Service 생성에 필요한 Redis 의존성만 제외한다.
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private String attractionName;
    private final List<Long> fixtureIds = new ArrayList<>();

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
        // name 컬럼의 최대 길이 50자 이내이며, 테스트별로 독립적인 이름을 사용한다.
        attractionName = "kan103-" + UUID.randomUUID();
        fixtureIds.clear();
    }

    @AfterEach
    void tearDown() {
        // 등록으로 생성된 행과, 수정이 rollback되어 원래 이름이 남은 fixture를 함께 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("""
                            DELETE FROM Attraction a WHERE a.name = :name
                            """)
                    .setParameter("name", attractionName)
                    .executeUpdate();
            for (Long fixtureId : fixtureIds) {
                Attraction attraction = entityManager.find(Attraction.class, fixtureId);
                if (attraction != null) {
                    entityManager.remove(attraction);
                }
            }
        });
    }

    @Test
    @DisplayName("같은 이름의 두 등록 요청이 중복 검사를 함께 통과해도 하나만 저장되고 나머지는 이름 중복으로 거부된다")
    void createAttraction_ConcurrentSameName_OnlyOneSucceeds() throws Exception {
        // given
        CountDownLatch duplicateChecksCompleted = new CountDownLatch(2);
        CreateAttractionRequest request = new CreateAttractionRequest(
                attractionName, "이름 중복 동시 등록 테스트", "A구역", 10, 5
        );
        doAnswer(invocation -> {
            Long count = entityManager.createQuery("""
                            SELECT COUNT(a) FROM Attraction a
                            WHERE a.name = :name AND a.deletedAt IS NULL
                            """, Long.class)
                    .setParameter("name", attractionName)
                    .getSingleResult();
            assertThat(count).as("각 요청의 사전 검사 시점에는 같은 이름이 없어야 한다").isZero();
            duplicateChecksCompleted.countDown();
            awaitDuplicateChecks(duplicateChecksCompleted);
            return count > 0;
        }).when(attractionRepository).existsByNameAndDeletedAtIsNull(attractionName);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<RegistrationResult> first = executor.submit(() -> register(request));
            Future<RegistrationResult> second = executor.submit(() -> register(request));
            // Service 프록시의 반환까지 기다리므로 성공 건수는 commit 완료 기준이다.
            List<RegistrationResult> results = List.of(
                    first.get(50, TimeUnit.SECONDS), second.get(50, TimeUnit.SECONDS)
            );
            long successCount = results.stream().filter(result -> result.failure() == null).count();
            List<RuntimeException> failures = results.stream()
                    .map(RegistrationResult::failure)
                    .filter(Objects::nonNull)
                    .toList();
            Long storedCount = transactionTemplate.execute(status -> entityManager.createQuery("""
                            SELECT COUNT(a) FROM Attraction a
                            WHERE a.name = :name AND a.deletedAt IS NULL
                            """, Long.class)
                    .setParameter("name", attractionName)
                    .getSingleResult());

            // then
            assertSoftly(softly -> {
                softly.assertThat(successCount).as("등록 성공 건수").isEqualTo(1);
                softly.assertThat(failures).as("거부된 등록 요청").hasSize(1);
                softly.assertThat(storedCount).as("DB에 저장된 활성 어트랙션 수").isEqualTo(1L);
                for (RuntimeException failure : failures) {
                    softly.assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NAME_DUPLICATED));
                }
            });
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("서로 다른 어트랙션을 같은 이름으로 동시에 수정하면 하나만 성공하고 나머지는 이름 중복으로 거부된다")
    void updateAttraction_ConcurrentSameName_OnlyOneSucceeds() throws Exception {
        // given
        fixtureIds.addAll(Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction first = Attraction.create(attractionName + "-a", "첫 번째 설명", "A구역", 10, 5);
            Attraction second = Attraction.create(attractionName + "-b", "두 번째 설명", "B구역", 10, 5);
            entityManager.persist(first);
            entityManager.persist(second);
            return List.of(first.getId(), second.getId());
        })));
        Map<Long, String> originalNames = Map.of(
                fixtureIds.get(0), attractionName + "-a",
                fixtureIds.get(1), attractionName + "-b"
        );
        CountDownLatch duplicateChecksCompleted = new CountDownLatch(2);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                attractionName, null, null, null, null
        );
        for (Long fixtureId : fixtureIds) {
            doAnswer(invocation -> {
                // 각 요청은 자기 행을 잠근 상태이며, 대상 이름이 없는지 실제 DB에서 확인한다.
                Long count = entityManager.createQuery("""
                                SELECT COUNT(a) FROM Attraction a
                                WHERE a.name = :name AND a.id <> :id AND a.deletedAt IS NULL
                                """, Long.class)
                        .setParameter("name", attractionName)
                        .setParameter("id", fixtureId)
                        .getSingleResult();
                assertThat(count).as("각 수정 요청의 사전 검사 시점에는 대상 이름이 없어야 한다").isZero();
                duplicateChecksCompleted.countDown();
                awaitDuplicateChecks(duplicateChecksCompleted);
                return count > 0;
            }).when(attractionRepository).existsByNameAndIdNotAndDeletedAtIsNull(attractionName, fixtureId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<RenameResult> first = executor.submit(() -> rename(fixtureIds.get(0), request));
            Future<RenameResult> second = executor.submit(() -> rename(fixtureIds.get(1), request));
            List<RenameResult> results = List.of(
                    first.get(50, TimeUnit.SECONDS), second.get(50, TimeUnit.SECONDS)
            );
            long successCount = results.stream().filter(result -> result.failure() == null).count();
            List<RuntimeException> failures = results.stream()
                    .map(RenameResult::failure)
                    .filter(Objects::nonNull)
                    .toList();
            List<RenameSnapshot> snapshots = Objects.requireNonNull(transactionTemplate.execute(status ->
                    fixtureIds.stream()
                            .map(id -> {
                                Attraction attraction = entityManager.find(Attraction.class, id);
                                return new RenameSnapshot(id, attraction.getName());
                            })
                            .toList()));
            long renamedCount = snapshots.stream()
                    .filter(snapshot -> attractionName.equals(snapshot.name()))
                    .count();

            // then
            assertSoftly(softly -> {
                softly.assertThat(successCount).as("이름 수정 성공 건수").isEqualTo(1);
                softly.assertThat(failures).as("거부된 수정 요청").hasSize(1);
                softly.assertThat(renamedCount).as("동일 이름으로 변경된 DB 행 수").isEqualTo(1);
                for (RuntimeException failure : failures) {
                    softly.assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NAME_DUPLICATED));
                }
                for (RenameResult result : results) {
                    String expectedName = result.failure() == null
                            ? attractionName : originalNames.get(result.attractionId());
                    softly.assertThat(snapshots)
                            .as("성공한 행은 새 이름을 저장하고 실패한 행은 기존 이름을 유지해야 한다")
                            .contains(new RenameSnapshot(result.attractionId(), expectedName));
                }
            });
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("삭제된 어트랙션의 이름으로 신규 등록할 수 있고 기존 삭제 이력은 유지된다")
    void createAttraction_DeletedNameExists_ReusesNameAndPreservesHistory() {
        // given
        Long deletedId = createFixture(attractionName);
        attractionService.deleteAttraction(ADMIN_ID, deletedId);
        NameState deletedState = readNameHistory().get(0);
        assertThat(deletedState.status()).isEqualTo(OperationStatus.DELETED);
        assertThat(deletedState.deletedAt()).isNotNull();
        assertThat(deletedState.activeAttraction()).isNull();

        // when
        Long createdId = createFixture(attractionName);

        // then
        assertThat(createdId).isNotEqualTo(deletedId);
        assertThat(readNameHistory()).containsExactlyInAnyOrder(
                deletedState,
                new NameState(createdId, attractionName, OperationStatus.OPERATING, null, 1)
        );
    }

    @Test
    @DisplayName("다른 어트랙션을 삭제된 이름으로 수정할 수 있고 기존 삭제 이력은 유지된다")
    void updateAttraction_DeletedNameExists_ReusesNameAndPreservesHistory() {
        // given
        Long deletedId = createFixture(attractionName);
        attractionService.deleteAttraction(ADMIN_ID, deletedId);
        NameState deletedState = readNameHistory().get(0);
        assertThat(deletedState.status()).isEqualTo(OperationStatus.DELETED);
        assertThat(deletedState.deletedAt()).isNotNull();
        assertThat(deletedState.activeAttraction()).isNull();
        Long targetId = createFixture(attractionName + "-other");
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                attractionName, null, null, null, null
        );

        // when
        SearchAttractionResponse response = attractionService.updateAttraction(ADMIN_ID, targetId, request);

        // then
        assertThat(response.attractionId()).isEqualTo(targetId);
        assertThat(response.name()).isEqualTo(attractionName);
        assertThat(readNameHistory()).containsExactlyInAnyOrder(
                deletedState,
                new NameState(targetId, attractionName, OperationStatus.OPERATING, null, 1)
        );
    }

    @Test
    @DisplayName("재사용한 이름의 어트랙션을 다시 삭제해도 같은 이름으로 등록하고 여러 삭제 이력을 보존할 수 있다")
    void createAttraction_ReusedNameDeletedAgain_PreservesMultipleDeletedRows() {
        // given
        Long firstId = createFixture(attractionName);
        attractionService.deleteAttraction(ADMIN_ID, firstId);
        Long secondId = createFixture(attractionName);
        attractionService.deleteAttraction(ADMIN_ID, secondId);
        List<NameState> deletedHistory = readNameHistory();
        assertThat(deletedHistory).hasSize(2).allSatisfy(state -> {
            assertThat(state.status()).isEqualTo(OperationStatus.DELETED);
            assertThat(state.deletedAt()).isNotNull();
            assertThat(state.activeAttraction()).isNull();
        });

        // when
        Long thirdId = createFixture(attractionName);

        // then
        assertThat(thirdId).isNotIn(firstId, secondId);
        List<NameState> history = readNameHistory();
        assertThat(history).hasSize(3).containsAll(deletedHistory);
        assertThat(history).contains(
                new NameState(thirdId, attractionName, OperationStatus.OPERATING, null, 1)
        );
    }

    private Long createFixture(String name) {
        CreateAttractionResponse response = attractionService.createAttraction(
                ADMIN_ID, new CreateAttractionRequest(name, "이름 재사용 테스트", "A구역", 10, 5)
        );
        fixtureIds.add(response.attractionId());
        return response.attractionId();
    }

    private List<NameState> readNameHistory() {
        // 별도 트랜잭션에서 삭제 이력을 포함해 읽어 DB가 계산한 보조 컬럼 값도 검증한다.
        return Objects.requireNonNull(transactionTemplate.execute(status -> entityManager.createQuery("""
                        SELECT a FROM Attraction a WHERE a.name = :name ORDER BY a.id
                        """, Attraction.class)
                .setParameter("name", attractionName)
                .getResultList()
                .stream()
                .map(attraction -> new NameState(
                        attraction.getId(), attraction.getName(), attraction.getOperationStatus(),
                        attraction.getDeletedAt(), attraction.getActiveAttraction()
                ))
                .toList()));
    }

    private RenameResult rename(Long attractionId, UpdateAttractionRequest request) {
        try {
            attractionService.updateAttraction(ADMIN_ID, attractionId, request);
            return new RenameResult(attractionId, null);
        } catch (RuntimeException exception) {
            return new RenameResult(attractionId, exception);
        }
    }

    private RegistrationResult register(CreateAttractionRequest request) {
        try {
            CreateAttractionResponse response = attractionService.createAttraction(ADMIN_ID, request);
            return new RegistrationResult(response.attractionId(), null);
        } catch (RuntimeException exception) {
            // DB 제약 위반 등도 결과로 수집하여 업무 오류로 처리됐는지 함께 검증한다.
            return new RegistrationResult(null, exception);
        }
    }

    private void awaitDuplicateChecks(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 요청의 이름 중복 검사 완료 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("이름 중복 검사 대기 중 인터럽트 발생", exception);
        }
    }

    private record RegistrationResult(Long attractionId, RuntimeException failure) {
    }

    private record RenameResult(Long attractionId, RuntimeException failure) {
    }

    private record RenameSnapshot(Long attractionId, String name) {
    }

    private record NameState(Long attractionId, String name, OperationStatus status,
                             Instant deletedAt, Integer activeAttraction) {
    }
}
