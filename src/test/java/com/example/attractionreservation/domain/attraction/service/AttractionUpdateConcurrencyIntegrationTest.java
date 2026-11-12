package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실제 Service와 Repository를 사용해 일반 수정과 운영 중단·삭제의 두 선행 순서를 검증한다.
// 외부 트랜잭션의 commit만 지연시켜 Service가 획득한 행 잠금을 유지한다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionService.class,
        ReservationUpcomingReservationReader.class,
        AttractionUpdateConcurrencyIntegrationTest.MySqlConfig.class})
class AttractionUpdateConcurrencyIntegrationTest {

    private static final Long ADMIN_ID = 99L;

    @Autowired
    private AttractionService attractionService;

    // 예약 수 검사는 실제 구현을 사용하고, Redis 캐시 삭제만 제외한다.
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Long attractionId;

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
        attractionId = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction attraction = Attraction.create(
                    "일반 수정 경합 테스트", "기존 설명", "A구역", 10, 5
            );
            entityManager.persist(attraction);
            return attraction.getId();
        }));
    }

    @AfterEach
    void tearDown() {
        if (attractionId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            if (attraction != null) {
                entityManager.remove(attraction);
            }
        });
    }

    @Test
    @DisplayName("일반 수정이 먼저 잠그면 삭제가 기다린 뒤 수정된 어트랙션을 삭제한다")
    void updateAttraction_UpdateLocksFirst_DeleteWaitsAndPreservesChanges() throws Exception {
        // given
        CountDownLatch updateApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "실제로 변경할 설명", null, null, null
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<SearchAttractionResponse> updateFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        SearchAttractionResponse response = attractionService.updateAttraction(
                                ADMIN_ID, attractionId, request
                        );
                        updateApplied.countDown();
                        awaitSignal(allowCommit);
                        return response;
                    }));
            assertThat(updateApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> deleteFuture = executor.submit(() -> {
                deleteStarted.countDown();
                attractionService.deleteAttraction(ADMIN_ID, attractionId);
            });
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(deleteFuture);

            allowCommit.countDown();
            SearchAttractionResponse updateResponse = updateFuture.get(20, TimeUnit.SECONDS);
            deleteFuture.get(20, TimeUnit.SECONDS);

            // then
            assertThat(updateResponse.description()).isEqualTo("실제로 변경할 설명");
            AttractionSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(snapshot.status()).isEqualTo(OperationStatus.DELETED);
                softly.assertThat(snapshot.deletedAt()).isNotNull();
                softly.assertThat(snapshot.description()).isEqualTo("실제로 변경할 설명");
            });
        } finally {
            allowCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("삭제가 먼저 잠그면 일반 수정은 기다렸다가 NOT_FOUND로 거부되고 삭제 결과가 유지된다")
    void updateAttraction_DeleteLocksFirst_RejectsUpdateAndPreservesDeletion() throws Exception {
        // given
        CountDownLatch deleteApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "실제로 변경할 설명", null, null, null
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<?> deleteFuture = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        attractionService.deleteAttraction(ADMIN_ID, attractionId);
                        // 잠금이 유지되는 동안 삭제 UPDATE도 DB에 전달한다.
                        entityManager.flush();
                        deleteApplied.countDown();
                        awaitSignal(allowCommit);
                    }));
            assertThat(deleteApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> updateFuture = executor.submit(() -> {
                updateStarted.countDown();
                try {
                    return attractionService.updateAttraction(ADMIN_ID, attractionId, request);
                } catch (BusinessException exception) {
                    return exception;
                }
            });
            assertThat(updateStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(updateFuture);

            allowCommit.countDown();
            deleteFuture.get(20, TimeUnit.SECONDS);
            Object updateResult = updateFuture.get(20, TimeUnit.SECONDS);

            // then
            assertThat(updateResult).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND));
            AttractionSnapshot afterDelete = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(afterDelete.status()).isEqualTo(OperationStatus.DELETED);
                softly.assertThat(afterDelete.deletedAt()).isNotNull();
                softly.assertThat(afterDelete.description()).isEqualTo("기존 설명");
            });
            assertThat(readSnapshot()).isEqualTo(afterDelete);
        } finally {
            allowCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("일반 수정이 먼저 잠그면 운영 중단이 기다린 뒤 수정된 설명을 유지하며 상태를 변경한다")
    void updateAttraction_UpdateLocksFirst_StatusChangeWaitsAndPreservesChanges() throws Exception {
        // given
        CountDownLatch updateApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch statusChangeStarted = new CountDownLatch(1);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "실제로 변경할 설명", null, null, null
        );
        UpdateAttractionStatusRequest statusRequest = new UpdateAttractionStatusRequest(
                OperationStatus.SUSPENDED.name()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<SearchAttractionResponse> updateFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        SearchAttractionResponse response = attractionService.updateAttraction(
                                ADMIN_ID, attractionId, request
                        );
                        updateApplied.countDown();
                        awaitSignal(allowCommit);
                        return response;
                    }));
            assertThat(updateApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UpdateAttractionStatusResponse> statusFuture = executor.submit(() -> {
                statusChangeStarted.countDown();
                return attractionService.updateAttractionStatus(ADMIN_ID, attractionId, statusRequest);
            });
            assertThat(statusChangeStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(statusFuture);

            allowCommit.countDown();
            SearchAttractionResponse updateResponse = updateFuture.get(20, TimeUnit.SECONDS);
            UpdateAttractionStatusResponse statusResponse = statusFuture.get(20, TimeUnit.SECONDS);

            // then
            assertThat(updateResponse.description()).isEqualTo("실제로 변경할 설명");
            assertThat(statusResponse.previousStatus()).isEqualTo(OperationStatus.OPERATING);
            assertThat(statusResponse.operationStatus()).isEqualTo(OperationStatus.SUSPENDED);
            AttractionSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(snapshot.status()).isEqualTo(OperationStatus.SUSPENDED);
                softly.assertThat(snapshot.description()).isEqualTo("실제로 변경할 설명");
                softly.assertThat(snapshot.deletedAt()).isNull();
            });
        } finally {
            allowCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("운영 중단이 먼저 잠그면 일반 수정이 기다린 뒤 중단 상태를 유지하며 설명을 변경한다")
    void updateAttraction_StatusChangeLocksFirst_UpdatePreservesSuspendedStatus() throws Exception {
        // given
        CountDownLatch statusChangeApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "실제로 변경할 설명", null, null, null
        );
        UpdateAttractionStatusRequest statusRequest = new UpdateAttractionStatusRequest(
                OperationStatus.SUSPENDED.name()
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<UpdateAttractionStatusResponse> statusFuture = executor.submit(() ->
                    transactionTemplate.execute(status -> {
                        UpdateAttractionStatusResponse response = attractionService.updateAttractionStatus(
                                ADMIN_ID, attractionId, statusRequest
                        );
                        statusChangeApplied.countDown();
                        awaitSignal(allowCommit);
                        return response;
                    }));
            assertThat(statusChangeApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SearchAttractionResponse> updateFuture = executor.submit(() -> {
                updateStarted.countDown();
                return attractionService.updateAttraction(ADMIN_ID, attractionId, request);
            });
            assertThat(updateStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(updateFuture);

            allowCommit.countDown();
            UpdateAttractionStatusResponse statusResponse = statusFuture.get(20, TimeUnit.SECONDS);
            SearchAttractionResponse updateResponse = updateFuture.get(20, TimeUnit.SECONDS);

            // then
            assertThat(statusResponse.previousStatus()).isEqualTo(OperationStatus.OPERATING);
            assertThat(statusResponse.operationStatus()).isEqualTo(OperationStatus.SUSPENDED);
            assertThat(updateResponse.description()).isEqualTo("실제로 변경할 설명");
            assertThat(updateResponse.operationStatus()).isEqualTo(OperationStatus.SUSPENDED);
            AttractionSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(snapshot.status()).isEqualTo(OperationStatus.SUSPENDED);
                softly.assertThat(snapshot.description()).isEqualTo("실제로 변경할 설명");
                softly.assertThat(snapshot.deletedAt()).isNull();
            });
        } finally {
            allowCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("트랜잭션 commit 재개 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("트랜잭션 대기 중 인터럽트 발생", exception);
        }
    }

    private void assertStillRunning(Future<?> future) {
        // 일정 시간 동안 요청이 끝나지 않는지 확인하며, DB의 잠금 대기 테이블을 조회하지는 않는다.
        assertThatThrownBy(() -> future.get(500, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private AttractionSnapshot readSnapshot() {
        // 매번 별도 트랜잭션에서 읽어 다른 요청의 1차 캐시에 남은 값을 비교하지 않는다.
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            return new AttractionSnapshot(
                    attraction.getOperationStatus(), attraction.getDeletedAt(), attraction.getDescription()
            );
        }));
    }

    private record AttractionSnapshot(OperationStatus status, Instant deletedAt, String description) {
    }
}
