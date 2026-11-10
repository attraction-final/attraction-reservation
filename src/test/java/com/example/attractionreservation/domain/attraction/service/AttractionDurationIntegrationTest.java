package com.example.attractionreservation.domain.attraction.service;

import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionRequest;
import com.example.attractionreservation.domain.attraction.dto.SearchAttractionResponse;
import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.CreateAttractionSlotResponse;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeRequest;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.reservation.service.ReservationUpcomingReservationReader;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 실제 Service와 MySQL을 사용해 소요 시간 변경 제한 및 거부 후 DB 상태를 검증한다.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, AttractionService.class, AttractionSlotService.class,
        ReservationUpcomingReservationReader.class, AttractionDurationIntegrationTest.MySqlConfig.class})
class AttractionDurationIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 8, 10, 0);
    private static final LocalDateTime SLOT_START = NOW.withHour(14);

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private AttractionSlotService attractionSlotService;

    @Autowired
    private AttractionRepository attractionRepository;

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
            // 슬롯이 없거나 새 슬롯을 생성한 경우도 이번 어트랙션의 데이터만 정리한다.
            entityManager.createQuery("DELETE FROM AttractionSlot s WHERE s.attraction.id = :attractionId")
                    .setParameter("attractionId", fixture.attractionId()).executeUpdate();
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
        Throwable failure = catchThrowable(() -> updateAtFixedTime(request));

        // then
        // 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 조회해 응답이 아닌 실제 저장 결과를 비교한다.
        State after = readState();
        assertSoftly(softly -> {
            softly.assertThat(failure).as("운영 예정 슬롯이 있으면 소요 시간 변경 거부")
                    .isInstanceOf(BusinessException.class);
            if (failure instanceof BusinessException exception) {
                softly.assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED);
            }
            softly.assertThat(after).as("소요 시간·함께 요청한 설명·기존 슬롯 모두 변경되지 않음")
                    .isEqualTo(before);
        });
    }

    @ParameterizedTest(name = "미래 슬롯 상태: {0}")
    @EnumSource(value = SlotStatus.class, names = {"CLOSED", "SUSPENDED"})
    @DisplayName("미래 슬롯이 수동 마감이나 운영 중단이어도 취소된 슬롯이 아니면 소요 시간 변경을 거부한다")
    void updateAttraction_futureClosedOrSuspendedSlot_rejectsDurationChange(SlotStatus status) {
        // given
        transactionTemplate.executeWithoutResult(transaction ->
                entityManager.find(AttractionSlot.class, fixture.slotId()).changeStatus(status));
        State before = readState();
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, null, null, 20, null);

        // when & then
        assertThatThrownBy(() -> updateAtFixedTime(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED));
        assertThat(readState()).isEqualTo(before);
    }

    @Test
    @DisplayName("이미 시작했지만 종료되지 않은 슬롯이 있으면 소요 시간 변경을 거부한다")
    void updateAttraction_ongoingSlot_rejectsDurationChange() {
        // given
        // 09:55~10:05 슬롯이므로 기준 시각 10:00에는 진행 중이다.
        moveSlotTo(NOW.minusMinutes(5));
        State before = readState();
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, null, null, 20, null);

        // when & then
        assertThatThrownBy(() -> updateAtFixedTime(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED));
        assertThat(readState()).isEqualTo(before);
    }

    @ParameterizedTest(name = "슬롯 시작: 현재 시각 대비 {0}분")
    @ValueSource(ints = {-10, -11})
    @DisplayName("슬롯 종료 정각이거나 이미 종료된 슬롯만 있으면 소요 시간 변경을 허용하고 기존 슬롯은 유지한다")
    void updateAttraction_slotEndedAtOrBeforeNow_allowsDurationChange(int minutesFromNow) {
        // given
        moveSlotTo(NOW.plusMinutes(minutesFromNow));
        State before = readState();
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, "변경 설명", null, 20, null);

        // when
        SearchAttractionResponse response = updateAtFixedTime(request);

        // then
        assertThat(response.durationMinutes()).isEqualTo(20);
        assertThat(readState()).isEqualTo(new State(20, "변경 설명",
                before.startsAt(), before.endsAt(), before.reservedCount(), before.status()));
    }

    @Test
    @DisplayName("미래 슬롯이 취소 상태이면 소요 시간 변경을 허용하고 취소된 슬롯의 시간은 유지한다")
    void updateAttraction_onlyCancelledFutureSlot_allowsDurationChange() {
        // given
        transactionTemplate.executeWithoutResult(transaction ->
                entityManager.find(AttractionSlot.class, fixture.slotId()).cancel());
        State before = readState();
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, "변경 설명", null, 20, null);

        // when
        SearchAttractionResponse response = updateAtFixedTime(request);

        // then
        assertThat(response.durationMinutes()).isEqualTo(20);
        assertThat(readState()).isEqualTo(new State(20, "변경 설명",
                before.startsAt(), before.endsAt(), before.reservedCount(), SlotStatus.CANCELLED));
    }

    @ParameterizedTest(name = "요청한 소요 시간: {0}")
    @NullSource
    @ValueSource(ints = {10})
    @DisplayName("미래 슬롯이 있어도 소요 시간을 생략하거나 같은 값으로 요청하면 설명 수정을 허용한다")
    void updateAttraction_durationOmittedOrUnchanged_allowsDescriptionChange(Integer durationMinutes) {
        // given
        State before = readState();
        UpdateAttractionRequest request = new UpdateAttractionRequest(
                null, "변경 설명", null, durationMinutes, null);

        // when
        SearchAttractionResponse response = updateAtFixedTime(request);

        // then
        assertThat(response.durationMinutes()).isEqualTo(10);
        assertThat(response.description()).isEqualTo("변경 설명");
        assertThat(readState()).isEqualTo(new State(10, "변경 설명",
                before.startsAt(), before.endsAt(), before.reservedCount(), before.status()));
    }

    @Test
    @DisplayName("슬롯이 하나도 없으면 소요 시간 변경을 허용한다")
    void updateAttraction_noSlots_allowsDurationChange() {
        // given
        transactionTemplate.executeWithoutResult(transaction ->
                entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId())));
        UpdateAttractionRequest request = new UpdateAttractionRequest(null, "변경 설명", null, 20, null);

        // when
        SearchAttractionResponse response = updateAtFixedTime(request);

        // then
        assertThat(response.durationMinutes()).isEqualTo(20);
        transactionTemplate.executeWithoutResult(transaction -> {
            Attraction saved = entityManager.find(Attraction.class, fixture.attractionId());
            assertThat(saved.getDurationMinutes()).isEqualTo(20);
            assertThat(saved.getDescription()).isEqualTo("변경 설명");
            Long slotCount = entityManager.createQuery(
                            "SELECT COUNT(s) FROM AttractionSlot s WHERE s.attraction.id = :attractionId", Long.class)
                    .setParameter("attractionId", fixture.attractionId()).getSingleResult();
            assertThat(slotCount).isZero();
        });
    }

    @Test
    @DisplayName("소요 시간 변경 후 실제 슬롯 등록 시 새 소요 시간을 적용하고 과거 슬롯 시간은 유지한다")
    void createAttractionSlot_afterDurationChange_usesNewDurationAndPreservesPastSlot() {
        // given
        moveSlotTo(SLOT_START.minusDays(1));
        State before = readState();
        UpdateAttractionRequest updateRequest = new UpdateAttractionRequest(null, null, null, 20, null);
        CreateAttractionSlotRequest createRequest = new CreateAttractionSlotRequest(SLOT_START, 10);

        // when
        CreateAttractionSlotResponse response;
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            attractionService.updateAttraction(ADMIN_ID, fixture.attractionId(), updateRequest);
            // 엔티티 생성 메서드만 호출하지 않고 실제 슬롯 Service의 검증·저장까지 실행한다.
            response = attractionSlotService.createAttractionSlot(
                    ADMIN_ID, fixture.attractionId(), createRequest);
        }

        // then
        assertThat(response.slotId()).isNotEqualTo(fixture.slotId());
        assertThat(response.startsAt()).isEqualTo(SLOT_START);
        assertThat(response.endsAt()).isEqualTo(SLOT_START.plusMinutes(20));
        assertThat(readState()).isEqualTo(new State(20, before.description(),
                before.startsAt(), before.endsAt(), before.reservedCount(), before.status()));
        transactionTemplate.executeWithoutResult(transaction -> {
            AttractionSlot created = entityManager.find(AttractionSlot.class, response.slotId());
            assertThat(created.getAttraction().getId()).isEqualTo(fixture.attractionId());
            assertThat(created.getStartsAt()).isEqualTo(SLOT_START);
            assertThat(created.getEndsAt()).isEqualTo(SLOT_START.plusMinutes(20));
            assertThat(created.getReservedCount()).isZero();
            assertThat(created.getStatus()).isEqualTo(SlotStatus.OPEN);
        });
    }

    @ParameterizedTest(name = "슬롯 등록 선행 여부: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 등록과 소요 시간 변경이 겹쳐도 먼저 커밋된 작업에 맞춰 변경 제한과 슬롯 길이를 유지한다")
    void createSlot_overlapsDurationChange_preservesScheduleConsistency(boolean slotRegistrationFirst)
            throws Exception {
        // given
        // 최초에는 과거 슬롯만 남겨 소요 시간 변경과 새 슬롯 등록이 각각 가능한 상태로 만든다.
        moveSlotTo(SLOT_START.minusDays(1));
        State before = readState();
        CountDownLatch firstWritten = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondLockRequested = new CountDownLatch(1);
        AtomicReference<Thread> secondThread = new AtomicReference<>();

        // 실제 잠금 조회 직전에 후행 작업의 도달 여부만 알리고 조회·결과는 그대로 실행한다.
        var advisor = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
            if (Thread.currentThread() == secondThread.get()
                    && invocation.getMethod().getName().equals("findActiveByIdForUpdate")) {
                secondLockRequested.countDown();
            }
            return invocation.proceed();
        });
        Advised repositoryProxy = (Advised) attractionRepository;
        repositoryProxy.addAdvisor(0, advisor);
        var executor = Executors.newFixedThreadPool(2);

        try {
            // when: 선행 Service 작업을 실제 수행하고 외부 트랜잭션의 커밋 직전에 멈춘다.
            var firstFuture = executor.submit(() -> transactionTemplate.execute(transaction -> {
                Long createdSlotId = executeSlotOrDurationChange(slotRegistrationFirst);
                entityManager.flush();
                firstWritten.countDown();
                awaitSignal(allowFirstCommit, "선행 작업 커밋");
                return createdSlotId;
            }));
            awaitSignal(firstWritten, "선행 작업 저장");

            var secondFuture = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                try {
                    return new OperationResult(executeSlotOrDurationChange(!slotRegistrationFirst), null);
                } catch (BusinessException exception) {
                    return new OperationResult(null, exception.getErrorCode());
                }
            });
            awaitSignal(secondLockRequested, "후행 작업의 어트랙션 잠금 요청");

            // 미완료 여부만으로 DB 잠금을 증명하지 않고, 아래의 최종 응답·DB 상태도 함께 검증한다.
            assertThatThrownBy(() -> secondFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowFirstCommit.countDown();
            Long firstSlotId = firstFuture.get(10, TimeUnit.SECONDS);
            OperationResult second = secondFuture.get(10, TimeUnit.SECONDS);

            // then
            int expectedDuration = slotRegistrationFirst ? 10 : 20;
            String expectedDescription = slotRegistrationFirst ? before.description() : "동시 수정 설명";
            assertThat(second.errorCode()).isEqualTo(slotRegistrationFirst
                    ? ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED : null);
            Long createdSlotId = slotRegistrationFirst ? firstSlotId : second.createdSlotId();
            assertThat(createdSlotId).isNotNull();
            assertThat(readState()).isEqualTo(new State(expectedDuration, expectedDescription,
                    before.startsAt(), before.endsAt(), before.reservedCount(), before.status()));
            transactionTemplate.executeWithoutResult(transaction -> {
                AttractionSlot created = entityManager.find(AttractionSlot.class, createdSlotId);
                assertThat(created.getAttraction().getId()).isEqualTo(fixture.attractionId());
                assertThat(created.getStartsAt()).isEqualTo(SLOT_START);
                assertThat(created.getEndsAt()).isEqualTo(SLOT_START.plusMinutes(expectedDuration));
                assertThat(created.getStatus()).isEqualTo(SlotStatus.OPEN);
                assertThat(created.getReservedCount()).isZero();
                Long slotCount = entityManager.createQuery(
                                "SELECT COUNT(s) FROM AttractionSlot s WHERE s.attraction.id = :attractionId", Long.class)
                        .setParameter("attractionId", fixture.attractionId()).getSingleResult();
                assertThat(slotCount).isEqualTo(2L);
            });
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("동시 작업 종료 후 fixture 정리").isTrue();
            } finally {
                repositoryProxy.removeAdvisor(advisor);
            }
        }
    }

    @ParameterizedTest(name = "슬롯 시각 수정 선행 여부: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("슬롯 시각 수정과 소요 시간 변경이 겹치면 두 순서 모두 소요 시간 변경을 거부하고 기존 길이로 슬롯을 수정한다")
    void updateSlotTime_overlapsDurationChange_preservesOriginalDuration(boolean slotTimeFirst)
            throws Exception {
        // given
        // 기존 미래 슬롯이 있으므로 어느 작업이 먼저여도 소요 시간 변경은 허용되지 않는다.
        State before = readState();
        CountDownLatch firstAttractionLocked = new CountDownLatch(1);
        CountDownLatch allowFirstContinue = new CountDownLatch(1);
        CountDownLatch secondLockRequested = new CountDownLatch(1);
        AtomicReference<Thread> firstThread = new AtomicReference<>();
        AtomicReference<Thread> secondThread = new AtomicReference<>();

        var advisor = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
            boolean attractionLock = invocation.getMethod().getName().equals("findActiveByIdForUpdate");
            if (attractionLock && Thread.currentThread() == secondThread.get()) {
                secondLockRequested.countDown();
            }
            Object result = invocation.proceed();
            // 조회 결과는 실제 DB 값이며 선행 작업의 어트랙션 잠금 획득 직후에만 멈춘다.
            if (attractionLock && Thread.currentThread() == firstThread.get()) {
                firstAttractionLocked.countDown();
                awaitSignal(allowFirstContinue, "선행 수정 작업 재개");
            }
            return result;
        });
        Advised repositoryProxy = (Advised) attractionRepository;
        repositoryProxy.addAdvisor(0, advisor);
        var executor = Executors.newFixedThreadPool(2);

        try {
            // when
            var firstFuture = executor.submit(() -> {
                firstThread.set(Thread.currentThread());
                return executeSlotTimeOrDurationChange(slotTimeFirst);
            });
            awaitSignal(firstAttractionLocked, "선행 수정 작업의 어트랙션 잠금 획득");
            var secondFuture = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return executeSlotTimeOrDurationChange(!slotTimeFirst);
            });
            awaitSignal(secondLockRequested, "후행 수정 작업의 어트랙션 잠금 요청");
            assertThatThrownBy(() -> secondFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowFirstContinue.countDown();
            ErrorCode firstError = firstFuture.get(10, TimeUnit.SECONDS);
            ErrorCode secondError = secondFuture.get(10, TimeUnit.SECONDS);

            // then: 잠금 시간 초과·데드락은 업무상 거부로 인정하지 않고 Future.get에서 실패시킨다.
            assertThat(firstError).isEqualTo(slotTimeFirst
                    ? null : ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED);
            assertThat(secondError).isEqualTo(slotTimeFirst
                    ? ErrorCode.ATTRACTION_DURATION_CHANGE_NOT_ALLOWED : null);
            assertThat(readState()).isEqualTo(new State(10, before.description(),
                    SLOT_START.plusHours(2), SLOT_START.plusHours(2).plusMinutes(10),
                    before.reservedCount(), before.status()));
            transactionTemplate.executeWithoutResult(transaction -> {
                Long slotCount = entityManager.createQuery(
                                "SELECT COUNT(s) FROM AttractionSlot s WHERE s.attraction.id = :attractionId", Long.class)
                        .setParameter("attractionId", fixture.attractionId()).getSingleResult();
                assertThat(slotCount).isEqualTo(1L);
            });
        } finally {
            allowFirstContinue.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("동시 작업 종료 후 fixture 정리").isTrue();
            } finally {
                repositoryProxy.removeAdvisor(advisor);
            }
        }
    }

    private ErrorCode executeSlotTimeOrDurationChange(boolean changeSlotTime) {
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            if (changeSlotTime) {
                attractionSlotService.updateAttractionSlotTime(ADMIN_ID, fixture.slotId(),
                        new UpdateAttractionSlotTimeRequest(SLOT_START.plusHours(2)));
            } else {
                attractionService.updateAttraction(ADMIN_ID, fixture.attractionId(),
                        new UpdateAttractionRequest(null, "동시 수정 설명", null, 20, null));
            }
            return null;
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    // static mock은 thread마다 적용해야 하므로 실제 Service를 실행하는 작업 스레드 안에서 만든다.
    private Long executeSlotOrDurationChange(boolean registerSlot) {
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            if (registerSlot) {
                return attractionSlotService.createAttractionSlot(ADMIN_ID, fixture.attractionId(),
                        new CreateAttractionSlotRequest(SLOT_START, 10)).slotId();
            }
            attractionService.updateAttraction(ADMIN_ID, fixture.attractionId(),
                    new UpdateAttractionRequest(null, "동시 수정 설명", null, 20, null));
            return null;
        }
    }

    private static void awaitSignal(CountDownLatch signal, String step) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(step + " 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(step + " 대기 중 중단됐습니다.", exception);
        }
    }

    private record OperationResult(Long createdSlotId, ErrorCode errorCode) {
    }

    private SearchAttractionResponse updateAtFixedTime(UpdateAttractionRequest request) {
        try (MockedStatic<KstTime> time = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            time.when(KstTime::now).thenReturn(NOW);
            return attractionService.updateAttraction(ADMIN_ID, fixture.attractionId(), request);
        }
    }

    // 경계값 fixture 구성용이다. 실제 관리자 슬롯 시각 수정 Service를 호출하는 테스트는 아니다.
    private void moveSlotTo(LocalDateTime startsAt) {
        transactionTemplate.executeWithoutResult(transaction ->
                entityManager.find(AttractionSlot.class, fixture.slotId()).changeTime(startsAt));
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
