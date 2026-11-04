package com.example.attractionreservation.domain.attractionSlot.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotCapacityRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotStatusRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeRequest;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 실제 슬롯 Service와 MySQL을 사용해 작업별 commit·rollback 결과와 로그 시점을 함께 검증한다.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionSlotService.class,
        AttractionSlotLogAfterCommitIntegrationTest.MySqlConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class AttractionSlotLogAfterCommitIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);
    private static final LocalDateTime ORIGINAL_START = NOW.withHour(14);
    private static final LocalDateTime CHANGED_START = NOW.withHour(16);

    @Autowired
    private AttractionSlotService attractionSlotService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;
    private Long attractionId;
    private Long slotId;
    private Long createdSlotId;

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
        slotId = Objects.requireNonNull(transactionTemplate.execute(status -> {
            Attraction attraction = Attraction.create(
                    "슬롯로그-" + UUID.randomUUID(), "슬롯 로그 테스트", "A구역", 30, 5
            );
            entityManager.persist(attraction);
            attractionId = attraction.getId();
            AttractionSlot slot = AttractionSlot.create(attraction, ORIGINAL_START, 10);
            entityManager.persist(slot);
            return slot.getId();
        }));
    }

    @AfterEach
    void tearDown() {
        if (attractionId == null) {
            return;
        }
        // 등록이 rollback된 경우 추가 ID의 행은 없을 수 있다.
        transactionTemplate.executeWithoutResult(status -> {
            removeSlotIfPresent(createdSlotId);
            removeSlotIfPresent(slotId);
            Attraction attraction = entityManager.find(Attraction.class, attractionId);
            if (attraction != null) {
                entityManager.remove(attraction);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 등록은 commit 시에만 DB에 저장되고 완료 로그가 한 번 남는다")
    void createAttractionSlot_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        String event = "ATTRACTION_SLOT_CREATED";
        CreateAttractionSlotRequest request = new CreateAttractionSlotRequest(CHANGED_START, 20);

        // when
        TransactionResult result = executeOperation(commit, event, output, () -> {
            createdSlotId = attractionSlotService.createAttractionSlot(ADMIN_ID, attractionId, request).slotId();
            return createdSlotId;
        });
        SlotSnapshot saved = readSlot(result.slotId());

        // then
        assertThat(saved).isEqualTo(commit ? expectedSlot(CHANGED_START, 20, SlotStatus.OPEN) : null);
        assertCompletionLogs(result, commit, event, output,
                "attractionId=" + attractionId, "userId=" + ADMIN_ID,
                "startsAt=" + CHANGED_START, "capacity=20");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 시간 변경은 commit 시에만 반영되고 변경 전·후 시각이 완료 로그에 남는다")
    void updateAttractionSlotTime_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        String event = "ATTRACTION_SLOT_TIME_CHANGED";
        SlotSnapshot before = readSlot(slotId);
        UpdateAttractionSlotTimeRequest request = new UpdateAttractionSlotTimeRequest(CHANGED_START);

        // when
        TransactionResult result = executeOperation(commit, event, output, () -> {
            attractionSlotService.updateAttractionSlotTime(ADMIN_ID, slotId, request);
            return slotId;
        });

        // then
        assertThat(readSlot(slotId)).isEqualTo(commit ? expectedSlot(CHANGED_START, 10, SlotStatus.OPEN) : before);
        assertCompletionLogs(result, commit, event, output,
                "attractionId=" + attractionId, "userId=" + ADMIN_ID,
                "previousStartsAt=" + ORIGINAL_START, "currentStartsAt=" + CHANGED_START);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 정원 변경은 commit 시에만 반영되고 변경 전·후 정원과 인원·상태를 기록한다")
    void updateAttractionSlotCapacity_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        String event = "ATTRACTION_SLOT_CAPACITY_CHANGED";
        SlotSnapshot before = readSlot(slotId);
        UpdateAttractionSlotCapacityRequest request = new UpdateAttractionSlotCapacityRequest(20);

        // when
        TransactionResult result = executeOperation(commit, event, output, () -> {
            attractionSlotService.updateAttractionSlotCapacity(ADMIN_ID, slotId, request);
            return slotId;
        });

        // then
        assertThat(readSlot(slotId)).isEqualTo(commit ? expectedSlot(ORIGINAL_START, 20, SlotStatus.OPEN) : before);
        assertCompletionLogs(result, commit, event, output, "userId=" + ADMIN_ID,
                "previousCapacity=10 currentCapacity=20 reservedCount=0 currentStatus=OPEN");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 상태 변경은 commit 시에만 반영되고 변경 전·후 상태가 완료 로그에 남는다")
    void updateAttractionSlotStatus_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        String event = "ATTRACTION_SLOT_STATUS_CHANGED";
        SlotSnapshot before = readSlot(slotId);
        UpdateAttractionSlotStatusRequest request = new UpdateAttractionSlotStatusRequest(SlotStatus.SUSPENDED.name());

        // when
        TransactionResult result = executeOperation(commit, event, output, () -> {
            attractionSlotService.updateAttractionSlotStatus(ADMIN_ID, slotId, request);
            return slotId;
        });

        // then
        assertThat(readSlot(slotId)).isEqualTo(commit ? expectedSlot(ORIGINAL_START, 10, SlotStatus.SUSPENDED) : before);
        assertCompletionLogs(result, commit, event, output, "userId=" + ADMIN_ID,
                "previousStatus=OPEN currentStatus=SUSPENDED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 삭제는 commit 시에만 취소 상태로 바뀌고 완료 로그가 한 번 남는다")
    void deleteAttractionSlot_TransactionCompletes_LogsOnlyWhenCommitted(boolean commit, CapturedOutput output) {
        // given
        String event = "ATTRACTION_SLOT_CANCELLED";
        SlotSnapshot before = readSlot(slotId);

        // when
        TransactionResult result = executeOperation(commit, event, output, () -> {
            attractionSlotService.deleteAttractionSlot(ADMIN_ID, slotId);
            return slotId;
        });

        // then
        assertThat(readSlot(slotId)).isEqualTo(commit ? expectedSlot(ORIGINAL_START, 10, SlotStatus.CANCELLED) : before);
        assertCompletionLogs(result, commit, event, output, "attractionId=" + attractionId,
                "userId=" + ADMIN_ID, "startsAt=" + ORIGINAL_START);
    }

    private TransactionResult executeOperation(boolean commit, String event, CapturedOutput output,
                                                Supplier<Long> operation) {
        // 슬롯 시간 검증을 위한 서버 시각만 고정하고, 나머지 KST 변환은 실제 동작을 유지한다.
        try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            timeMock.when(KstTime::now).thenReturn(NOW);
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                Long targetId = operation.get();
                List<String> beforeCompletion = eventLogs(output, event, targetId);
                if (!commit) {
                    status.setRollbackOnly();
                }
                return new TransactionResult(targetId, beforeCompletion);
            }));
        }
    }

    private void assertCompletionLogs(TransactionResult result, boolean commit, String event,
                                      CapturedOutput output, String... expectedFields) {
        assertThat(result.logsBeforeCompletion()).as("commit 전 완료 로그").isEmpty();
        List<String> logs = eventLogs(output, event, result.slotId());
        if (commit) {
            assertThat(logs).as("commit 후 완료 로그").singleElement().asString().contains(expectedFields);
        } else {
            assertThat(logs).as("rollback 후 완료 로그").isEmpty();
        }
    }

    private List<String> eventLogs(CapturedOutput output, String event, Long targetId) {
        return output.getAll().lines()
                .filter(line -> line.contains("event=" + event + " "))
                .filter(line -> line.contains("slotId=" + targetId + " "))
                .toList();
    }

    private SlotSnapshot readSlot(Long targetId) {
        // 별도 트랜잭션에서 commit 후 DB 값 또는 rollback 후 유지된 값을 읽는다.
        return transactionTemplate.execute(status -> {
            AttractionSlot slot = entityManager.find(AttractionSlot.class, targetId);
            if (slot == null) {
                return null;
            }
            return new SlotSnapshot(slot.getAttraction().getId(), slot.getStartsAt(), slot.getEndsAt(),
                    slot.getCapacity(), slot.getReservedCount(), slot.getRemainingCount(), slot.getStatus(), slot.isAutoClosed());
        });
    }

    private SlotSnapshot expectedSlot(LocalDateTime startsAt, int capacity, SlotStatus status) {
        return new SlotSnapshot(attractionId, startsAt, startsAt.plusMinutes(30), capacity, 0, capacity, status, false);
    }

    private void removeSlotIfPresent(Long targetId) {
        if (targetId != null) {
            AttractionSlot slot = entityManager.find(AttractionSlot.class, targetId);
            if (slot != null) {
                entityManager.remove(slot);
            }
        }
    }

    private record TransactionResult(Long slotId, List<String> logsBeforeCompletion) {
    }

    private record SlotSnapshot(Long attractionId, LocalDateTime startsAt, LocalDateTime endsAt,
                                int capacity, int reservedCount, int remainingCount, SlotStatus status, boolean autoClosed) {
    }
}
