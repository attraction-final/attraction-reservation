package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.CreateAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationUpcomingReservationReader;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실제 Service와 외부 트랜잭션을 사용해 commit 이후에만 완료 로그가 남는지 검증한다.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionService.class,
        ReservationUpcomingReservationReader.class, AttractionLogAfterCommitIntegrationTest.MySqlConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class AttractionLogAfterCommitIntegrationTest {

    private static final Long ADMIN_ID = 99L;

    @Autowired
    private AttractionService attractionService;

    // Redis 캐시 삭제는 제외하고 DB 트랜잭션과 완료 로그를 검증한다.
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Long attractionId;
    private Long createdAttractionId;

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
                    "kan107-" + UUID.randomUUID(), "기존 설명", "A구역", 10, 5
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
            // 등록 테스트에서 commit된 추가 fixture도 이번 테스트의 ID로만 정리한다.
            if (createdAttractionId != null) {
                Attraction created = entityManager.find(Attraction.class, createdAttractionId);
                if (created != null) {
                    entityManager.remove(created);
                }
            }
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            if (attraction != null) {
                entityManager.remove(attraction);
            }
        });
    }

    @Test
    @DisplayName("어트랙션 수정이 rollback되면 DB 변경과 완료 로그가 남지 않고 commit 전에도 완료 로그가 없다")
    void updateAttraction_OuterTransactionRolledBack_PreservesDataAndDoesNotLog(CapturedOutput output) {
        // given
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, "변경할 설명", null, 12, null);
        AtomicReference<List<String>> logsBeforeCompletion = new AtomicReference<>();

        // when
        SearchAttractionResponse attempted = transactionTemplate.execute(status -> {
            // 내부 flush는 실행되지만 최종 commit 여부는 이 외부 트랜잭션이 결정한다.
            SearchAttractionResponse response = attractionService.updateAttraction(ADMIN_ID, attractionId, request);
            logsBeforeCompletion.set(updateLogs(output));
            status.setRollbackOnly();
            return response;
        });
        AttractionSnapshot saved = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            return new AttractionSnapshot(attraction.getDescription(), attraction.getDurationMinutes());
        }));

        // then
        assertThat(attempted).isNotNull();
        assertThat(attempted.description()).isEqualTo("변경할 설명");
        assertThat(attempted.durationMinutes()).isEqualTo(12);
        assertSoftly(softly -> {
            softly.assertThat(saved).as("rollback 후 DB는 기존 값을 유지해야 한다")
                    .isEqualTo(new AttractionSnapshot("기존 설명", 10));
            softly.assertThat(logsBeforeCompletion.get()).as("commit 전 수정 완료 로그").isEmpty();
            softly.assertThat(updateLogs(output)).as("rollback 후 수정 완료 로그").isEmpty();
        });
    }

    @Test
    @DisplayName("어트랙션 수정 완료 로그는 commit 전에는 없고 commit 후 변경 전·후 값과 함께 정확히 한 번 남는다")
    void updateAttraction_OuterTransactionCommitted_PersistsDataAndLogsOnce(CapturedOutput output) {
        // given
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, "변경할 설명", null, 12, null);
        AtomicReference<List<String>> logsBeforeCompletion = new AtomicReference<>();

        // when
        SearchAttractionResponse response = transactionTemplate.execute(status -> {
            SearchAttractionResponse changed = attractionService.updateAttraction(ADMIN_ID, attractionId, request);
            // Service의 flush 이후에도 외부 트랜잭션이 commit되기 전에는 완료 로그가 없어야 한다.
            logsBeforeCompletion.set(updateLogs(output));
            return changed;
        });
        // execute 반환 시 commit과 afterCommit callback이 끝난 상태다.
        AttractionSnapshot saved = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            return new AttractionSnapshot(attraction.getDescription(), attraction.getDurationMinutes());
        }));
        List<String> logsAfterCommit = updateLogs(output);

        // then
        assertThat(response).isNotNull();
        assertThat(response.description()).isEqualTo("변경할 설명");
        assertThat(response.durationMinutes()).isEqualTo(12);
        assertSoftly(softly -> {
            softly.assertThat(saved).as("commit 후 DB에 수정 값이 저장되어야 한다")
                    .isEqualTo(new AttractionSnapshot("변경할 설명", 12));
            softly.assertThat(logsBeforeCompletion.get()).as("commit 전 수정 완료 로그").isEmpty();
            softly.assertThat(logsAfterCommit).as("commit 후 수정 완료 로그").singleElement().asString()
                    .contains("event=ATTRACTION_UPDATED attractionId=" + attractionId)
                    .contains("userId=" + ADMIN_ID)
                    .contains("previousDurationMinutes=10 currentDurationMinutes=12");
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("어트랙션 등록은 commit 전 로그가 없고 commit 시에만 저장 결과와 완료 로그가 남는다")
    void createAttraction_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        CreateAttractionRequest request = new CreateAttractionRequest(
                "등록로그-" + UUID.randomUUID(), "등록 설명", "A구역", 15, 5
        );
        AtomicReference<List<String>> beforeCompletion = new AtomicReference<>();

        // when
        CreateAttractionResponse response = transactionTemplate.execute(status -> {
            CreateAttractionResponse created = attractionService.createAttraction(ADMIN_ID, request);
            createdAttractionId = created.attractionId();
            beforeCompletion.set(eventLogs(output, "ATTRACTION_CREATED", createdAttractionId));
            if (!commit) {
                status.setRollbackOnly();
            }
            return created;
        });
        String savedName = transactionTemplate.execute(status -> {
            Attraction saved = entityManager.find(Attraction.class, createdAttractionId);
            return saved == null ? null : saved.getName();
        });

        // then
        assertThat(response).isNotNull();
        assertThat(savedName).as("commit 여부에 따른 신규 행 저장 결과")
                .isEqualTo(commit ? request.name() : null);
        assertCompletionLogs(output, "ATTRACTION_CREATED", createdAttractionId, beforeCompletion.get(), commit,
                "userId=" + ADMIN_ID, "durationMinutes=15");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("어트랙션 상태 변경은 commit 시에만 반영되고 변경 전·후 상태 로그가 한 번 남는다")
    void updateAttractionStatus_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        UpdateAttractionStatusRequest request = new UpdateAttractionStatusRequest(OperationStatus.MAINTENANCE.name());
        AtomicReference<List<String>> beforeCompletion = new AtomicReference<>();

        // when
        transactionTemplate.executeWithoutResult(status -> {
            attractionService.updateAttractionStatus(ADMIN_ID, attractionId, request);
            beforeCompletion.set(eventLogs(output, "ATTRACTION_STATUS_CHANGED", attractionId));
            if (!commit) {
                status.setRollbackOnly();
            }
        });
        AttractionState saved = readState();

        // then
        assertThat(saved.status()).isEqualTo(commit ? OperationStatus.MAINTENANCE : OperationStatus.OPERATING);
        assertThat(saved.deletedAt()).isNull();
        assertCompletionLogs(output, "ATTRACTION_STATUS_CHANGED", attractionId, beforeCompletion.get(), commit,
                "userId=" + ADMIN_ID, "previousStatus=OPERATING currentStatus=MAINTENANCE");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("어트랙션 삭제는 commit 시에만 삭제 상태·시각과 완료 로그가 남는다")
    void deleteAttraction_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        AtomicReference<List<String>> beforeCompletion = new AtomicReference<>();

        // when
        transactionTemplate.executeWithoutResult(status -> {
            attractionService.deleteAttraction(ADMIN_ID, attractionId);
            beforeCompletion.set(eventLogs(output, "ATTRACTION_DELETED", attractionId));
            if (!commit) {
                status.setRollbackOnly();
            }
        });
        AttractionState saved = readState();

        // then
        assertThat(saved.status()).isEqualTo(commit ? OperationStatus.DELETED : OperationStatus.OPERATING);
        if (commit) {
            assertThat(saved.deletedAt()).isNotNull();
        } else {
            assertThat(saved.deletedAt()).isNull();
        }
        assertCompletionLogs(output, "ATTRACTION_DELETED", attractionId, beforeCompletion.get(), commit,
                "userId=" + ADMIN_ID);
    }

    private AttractionState readState() {
        // 이전 영속성 컨텍스트의 값이 아닌 트랜잭션 종료 후 DB 값을 확인한다.
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction saved = entityManager.find(Attraction.class, attractionId);
            return new AttractionState(saved.getOperationStatus(), saved.getDeletedAt());
        }));
    }

    private void assertCompletionLogs(CapturedOutput output, String event, Long id,
                                      List<String> beforeCompletion, boolean commit, String... fields) {
        assertThat(beforeCompletion).as("트랜잭션 종료 전 완료 로그").isEmpty();
        List<String> completedLogs = eventLogs(output, event, id);
        if (commit) {
            assertThat(completedLogs).as("commit 후 완료 로그").singleElement().asString().contains(fields);
        } else {
            assertThat(completedLogs).as("rollback 후 완료 로그").isEmpty();
        }
    }

    private List<String> updateLogs(CapturedOutput output) {
        return eventLogs(output, "ATTRACTION_UPDATED", attractionId);
    }

    private List<String> eventLogs(CapturedOutput output, String event, Long id) {
        // 다른 테스트나 framework 로그를 제외하고 이번 fixture의 비즈니스 이벤트만 확인한다.
        return output.getAll().lines()
                .filter(line -> line.contains("event=" + event + " "))
                .filter(line -> line.contains("attractionId=" + id + " "))
                .toList();
    }

    private record AttractionSnapshot(String description, Integer durationMinutes) {
    }

    private record AttractionState(OperationStatus status, Instant deletedAt) {
    }
}
