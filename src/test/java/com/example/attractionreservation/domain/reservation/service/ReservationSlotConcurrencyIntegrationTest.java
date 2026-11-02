package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.cache.AttractionDetailCacheEvictor;
import com.example.attractionreservation.domain.attraction.dto.UpdateAttractionStatusRequest;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.service.AttractionService;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeRequest;
import com.example.attractionreservation.domain.attractionSlot.dto.UpdateAttractionSlotTimeResponse;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 실제 MySQL에서 예약 생성과 관리자 시간 변경의 두 선행 순서를 검증한다.
// 시간 변경·운영 상태 변경 검증에 실제 Service와 Repository를 사용하고 commit 시점만 제어한다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationService.class, UserService.class,
        TicketService.class, AttractionSlotService.class, AttractionService.class,
        ReservationUpcomingReservationReader.class, ReservationSlotConcurrencyIntegrationTest.MySqlConfig.class})
class ReservationSlotConcurrencyIntegrationTest {

    private static final Long ADMIN_ID = 99L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime ORIGINAL_START = NOW.withHour(16);
    private static final LocalDateTime CHANGED_START = NOW.withMinute(20);

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private AttractionSlotService attractionSlotService;

    @Autowired
    private AttractionService attractionService;

    // DB의 운영 상태·예약 수 검사는 실제 구현을 사용하고, Redis 캐시 삭제만 제외한다.
    @MockitoBean
    private AttractionDetailCacheEvictor attractionDetailCacheEvictor;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // UserService의 생성에 필요하지만 이 예약 생성 테스트에서는 호출하지 않는 의존성이다.
    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

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
        fixture = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = User.builder()
                    .email("kan101-" + UUID.randomUUID().toString().substring(0, 12) + "@test.com")
                    .password("test-password")
                    .nickname("슬롯경합회원")
                    .build();
            entityManager.persist(user);
            TicketProduct product = new TicketProduct(
                    "종일 이용권", 50000, "예약과 슬롯 변경 경합 테스트",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            Order order = Order.create(user, 50000, NOW.toLocalDate());
            entityManager.persist(order);
            OrderItem orderItem = OrderItem.create(order, product, 1);
            entityManager.persist(orderItem);
            Ticket ticket = new Ticket(NOW.toLocalDate(), orderItem);
            ticket.entryTicket(NOW.withHour(10).atZone(KstTime.ZONE).toInstant());
            entityManager.persist(ticket);

            Attraction attraction = Attraction.create("슬롯 경합 테스트", "테스트 설명", "A구역", 30, 5);
            entityManager.persist(attraction);
            AttractionSlot slot = AttractionSlot.create(attraction, ORIGINAL_START, 10);
            entityManager.persist(slot);
            return new Fixture(user.getId(), product.getId(), order.getId(), orderItem.getId(),
                    ticket.getId(), attraction.getId(), slot.getId());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 실패로 예약이 생성되어도 이번 fixture에 속한 데이터만 FK 참조 순서대로 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Reservation r where r.user.id = :userId")
                    .setParameter("userId", fixture.userId()).executeUpdate();
            entityManager.remove(entityManager.find(Ticket.class, fixture.ticketId()));
            entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
            entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
            entityManager.remove(entityManager.find(OrderItem.class, fixture.orderItemId()));
            entityManager.remove(entityManager.find(Order.class, fixture.orderId()));
            entityManager.remove(entityManager.find(TicketProduct.class, fixture.productId()));
            entityManager.remove(entityManager.find(User.class, fixture.userId()));
        });
    }

    @Test
    @DisplayName("예약이 먼저 슬롯을 잠그면 시간 변경은 예약 commit 후 거절되고 기존 시각과 인원을 유지한다")
    void createReservation_ReservationLocksFirst_RejectsSlotTimeChange() throws Exception {
        // given
        CountDownLatch reservationApplied = new CountDownLatch(1);
        CountDownLatch allowReservationCommit = new CountDownLatch(1);
        CountDownLatch adminStarted = new CountDownLatch(1);
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateReservationResponse> reservationResult = executor.submit(() ->
                    reserveAndWaitForCommit(request, reservationApplied, allowReservationCommit));
            assertThat(reservationApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UpdateAttractionSlotTimeResponse> adminResult = executor.submit(() -> {
                try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
                    timeMock.when(KstTime::now).thenReturn(NOW);
                    adminStarted.countDown();
                    return attractionSlotService.updateAttractionSlotTime(
                            ADMIN_ID, fixture.slotId(), new UpdateAttractionSlotTimeRequest(CHANGED_START)
                    );
                }
            });
            assertThat(adminStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(adminResult);
            allowReservationCommit.countDown();
            CreateReservationResponse response = reservationResult.get(20, TimeUnit.SECONDS);

            // then
            assertThatThrownBy(() -> adminResult.get(20, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class, businessException ->
                                    assertThat(businessException.getErrorCode()).isEqualTo(ErrorCode.SLOT_HAS_RESERVATIONS)));
            SlotSnapshot snapshot = readSnapshot();
            assertThat(response.startsAt()).isEqualTo(ORIGINAL_START);
            assertThat(response.slotId()).isEqualTo(fixture.slotId());
            assertThat(snapshot.startsAt()).isEqualTo(ORIGINAL_START);
            assertThat(snapshot.status()).isEqualTo(SlotStatus.OPEN);
            assertThat(snapshot.reservedCount()).isEqualTo(1);
            assertThat(snapshot.reservationCount()).isEqualTo(1);
        } finally {
            allowReservationCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("관리자 시간 변경이 먼저 잠그면 예약은 변경 commit 후 최신 마감 시각으로 거절되고 인원이 늘지 않는다")
    void createReservation_AdminTimeChangeLocksFirst_RejectsExpiredDeadline() throws Exception {
        // given
        CountDownLatch timeChangeApplied = new CountDownLatch(1);
        CountDownLatch allowTimeChangeCommit = new CountDownLatch(1);
        CountDownLatch reservationStarted = new CountDownLatch(1);
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<UpdateAttractionSlotTimeResponse> adminResult = executor.submit(() -> {
                try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
                    timeMock.when(KstTime::now).thenReturn(NOW);
                    return transactionTemplate.execute(status -> {
                        UpdateAttractionSlotTimeResponse response = attractionSlotService.updateAttractionSlotTime(
                                ADMIN_ID, fixture.slotId(), new UpdateAttractionSlotTimeRequest(CHANGED_START)
                        );
                        timeChangeApplied.countDown();
                        awaitSignal(allowTimeChangeCommit);
                        return response;
                    });
                }
            });
            assertThat(timeChangeApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ReservationResult> reservationResult = executor.submit(() -> {
                reservationStarted.countDown();
                return reserveAtFixedTime(request);
            });
            assertThat(reservationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(reservationResult);
            allowTimeChangeCommit.countDown();
            UpdateAttractionSlotTimeResponse adminResponse = adminResult.get(20, TimeUnit.SECONDS);
            assertThat(adminResponse.startsAt()).isEqualTo(CHANGED_START);
            assertThat(CHANGED_START.minusMinutes(30)).isBefore(NOW);

            ReservationResult result = reservationResult.get(20, TimeUnit.SECONDS);

            // then: 새 transaction에서 현재 슬롯과 실제 저장된 예약 수를 확인한다.
            SlotSnapshot afterReservation = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(result.response()).as("새 슬롯의 마감이 지났으므로 성공 응답이 나오면 안 된다").isNull();
                softly.assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_DEADLINE_PASSED));
                softly.assertThat(afterReservation.startsAt()).isEqualTo(CHANGED_START);
                softly.assertThat(afterReservation.status()).isEqualTo(SlotStatus.OPEN);
                softly.assertThat(afterReservation.reservedCount())
                        .as("기존 검증 시각=%s, 변경 시각=%s, 현재=%s: 마감 지난 예약의 인원이 반영되면 안 된다",
                                ORIGINAL_START, CHANGED_START, NOW)
                        .isZero();
                softly.assertThat(afterReservation.reservationCount()).isZero();
            });
        } finally {
            allowTimeChangeCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @ParameterizedTest(name = "관리자 변경={0}")
    @EnumSource(value = OperationStatus.class, names = {"SUSPENDED", "DELETED"})
    @DisplayName("예약이 먼저 어트랙션을 잠그면 운영 중단과 삭제는 예약 commit 후 예정 예약이 있어 거절된다")
    void createReservation_ReservationLocksFirst_RejectsAttractionChange(
            OperationStatus targetStatus
    ) throws Exception {
        // given
        CountDownLatch reservationApplied = new CountDownLatch(1);
        CountDownLatch allowReservationCommit = new CountDownLatch(1);
        CountDownLatch adminStarted = new CountDownLatch(1);
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateReservationResponse> reservationResult = executor.submit(() ->
                    reserveAndWaitForCommit(request, reservationApplied, allowReservationCommit));
            assertThat(reservationApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> adminResult = executor.submit(() -> {
                adminStarted.countDown();
                changeAttractionAtFixedTime(targetStatus);
            });
            assertThat(adminStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(adminResult);
            allowReservationCommit.countDown();
            CreateReservationResponse response = reservationResult.get(20, TimeUnit.SECONDS);

            // then
            assertThatThrownBy(() -> adminResult.get(20, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOfSatisfying(BusinessException.class, businessException ->
                                    assertThat(businessException.getErrorCode())
                                            .isEqualTo(ErrorCode.ATTRACTION_HAS_UPCOMING_RESERVATIONS)));
            SlotSnapshot snapshot = readSnapshot();
            assertThat(response.slotId()).isEqualTo(fixture.slotId());
            assertThat(response.startsAt()).isEqualTo(ORIGINAL_START);
            assertThat(snapshot.attractionStatus()).isEqualTo(OperationStatus.OPERATING);
            assertThat(snapshot.attractionDeletedAt()).isNull();
            assertThat(snapshot.reservationCount()).isEqualTo(1);
            assertThat(snapshot.reservedCount()).isEqualTo(1);
            assertThat(snapshot.startsAt()).isEqualTo(ORIGINAL_START);
        } finally {
            allowReservationCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @ParameterizedTest(name = "관리자 선행 변경={0}")
    @EnumSource(value = OperationStatus.class, names = {"SUSPENDED", "DELETED"})
    @DisplayName("운영 중단 또는 삭제가 먼저 어트랙션을 잠그면 예약은 commit 후 최신 상태를 확인하고 거절된다")
    void createReservation_AttractionChangeLocksFirst_RejectsNonOperatingAttraction(
            OperationStatus targetStatus
    ) throws Exception {
        // given
        CountDownLatch adminApplied = new CountDownLatch(1);
        CountDownLatch allowAdminCommit = new CountDownLatch(1);
        CountDownLatch reservationStarted = new CountDownLatch(1);
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<?> adminResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                changeAttractionAtFixedTime(targetStatus);
                adminApplied.countDown();
                awaitSignal(allowAdminCommit);
            }));
            assertThat(adminApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ReservationResult> reservationResult = executor.submit(() -> {
                reservationStarted.countDown();
                return reserveAtFixedTime(request);
            });
            assertThat(reservationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(reservationResult);
            allowAdminCommit.countDown();
            adminResult.get(20, TimeUnit.SECONDS);
            ReservationResult result = reservationResult.get(20, TimeUnit.SECONDS);

            // then
            SlotSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(result.response())
                        .as("관리자가 %s로 변경하고 commit한 뒤에는 예약에 성공하면 안 된다", targetStatus)
                        .isNull();
                softly.assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ATTRACTION_NOT_OPERATING));
                softly.assertThat(snapshot.attractionStatus()).isEqualTo(targetStatus);
                if (targetStatus == OperationStatus.DELETED) {
                    softly.assertThat(snapshot.attractionDeletedAt()).isNotNull();
                } else {
                    softly.assertThat(snapshot.attractionDeletedAt()).isNull();
                }
                softly.assertThat(snapshot.reservationCount()).isZero();
                softly.assertThat(snapshot.reservedCount()).isZero();
                softly.assertThat(snapshot.startsAt()).isEqualTo(ORIGINAL_START);
                softly.assertThat(snapshot.status()).isEqualTo(SlotStatus.OPEN);
            });
        } finally {
            allowAdminCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    private void changeAttractionAtFixedTime(OperationStatus targetStatus) {
        try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            timeMock.when(KstTime::now).thenReturn(NOW);
            if (targetStatus == OperationStatus.DELETED) {
                // DELETED는 상태 변경 요청이 아닌 기존 소프트 삭제 Service로 처리한다.
                attractionService.deleteAttraction(ADMIN_ID, fixture.attractionId());
            } else {
                attractionService.updateAttractionStatus(
                        ADMIN_ID, fixture.attractionId(), new UpdateAttractionStatusRequest(targetStatus.name())
                );
            }
        }
    }

    private CreateReservationResponse reserveAndWaitForCommit(
            CreateReservationRequest request, CountDownLatch applied, CountDownLatch allowCommit
    ) {
        try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            timeMock.when(KstTime::now).thenReturn(NOW);
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                // 실제 예약 Service의 transaction을 유지하고 commit만 지연한다. 별도 잠금은 추가하지 않는다.
                CreateReservationResponse response = reservationService.createReservation(fixture.userId(), request);
                applied.countDown();
                awaitSignal(allowCommit);
                return response;
            }));
        }
    }

    private void assertStillRunning(Future<?> result) {
        // 500ms 동안 미완료인지 확인한다. DB 내부 lock wait 정보를 직접 수집하는 검증은 아니다.
        assertThatThrownBy(() -> result.get(500, TimeUnit.MILLISECONDS))
                .as("선행 transaction이 commit되기 전에는 후행 요청이 완료되면 안 된다")
                .isInstanceOf(TimeoutException.class);
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("테스트 작업은 fixture 정리 전에 종료되어야 한다").isTrue();
        }
    }

    private ReservationResult reserveAtFixedTime(CreateReservationRequest request) {
        try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
            timeMock.when(KstTime::now).thenReturn(NOW);
            try {
                return new ReservationResult(reservationService.createReservation(fixture.userId(), request), null);
            } catch (RuntimeException exception) {
                return new ReservationResult(null, exception);
            }
        }
    }

    private SlotSnapshot readSnapshot() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            long reservationCount = entityManager.createQuery(
                            "select count(r) from Reservation r where r.attractionSlot.id = :slotId", Long.class)
                    .setParameter("slotId", fixture.slotId()).getSingleResult();
            Attraction attraction = slot.getAttraction();
            return new SlotSnapshot(slot.getStartsAt(), slot.getStatus(), slot.getReservedCount(), reservationCount,
                    attraction.getOperationStatus(), attraction.getDeletedAt());
        }));
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("transaction commit 재개 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("transaction commit 대기 중 인터럽트 발생", exception);
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId, Long orderItemId,
                           Long ticketId, Long attractionId, Long slotId) {
    }

    private record ReservationResult(CreateReservationResponse response, RuntimeException failure) {
    }

    private record SlotSnapshot(LocalDateTime startsAt, SlotStatus status, int reservedCount, long reservationCount,
                                OperationStatus attractionStatus, Instant attractionDeletedAt) {
    }
}
