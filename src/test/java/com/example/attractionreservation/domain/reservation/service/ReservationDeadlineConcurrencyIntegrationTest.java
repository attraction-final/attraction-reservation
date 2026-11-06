package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.auth.repository.RefreshTokenRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
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
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;

// 실제 MySQL 회원 행 잠금으로 예약 생성을 대기시키고, 대기 중 마감이 지나는 상황을 검증한다.
// 시간만 제어하며 회원·슬롯·어트랙션 조회와 예약·정원 변경은 실제 구현을 사용한다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, ReservationService.class, UserService.class,
        TicketService.class, AttractionSlotService.class,
        ReservationDeadlineConcurrencyIntegrationTest.MySqlConfig.class})
class ReservationDeadlineConcurrencyIntegrationTest {

    private static final LocalDateTime SLOT_START = LocalDateTime.of(2026, 11, 6, 14, 0);
    private static final LocalDateTime DEADLINE = SLOT_START.minusMinutes(30);
    private static final LocalDateTime BEFORE_DEADLINE = DEADLINE.minusSeconds(1);
    private static final LocalDateTime AFTER_DEADLINE = DEADLINE.plusSeconds(1);

    @Autowired
    private ReservationService reservationService;

    // 실제 처리는 유지하고, 회원 잠금 조회 직전에 도달했다는 신호만 전달한다.
    @MockitoSpyBean
    private UserService userService;

    // 슬롯·어트랙션 잠금 조회의 시작을 알리고 실제 Service 처리는 그대로 실행한다.
    @MockitoSpyBean
    private AttractionSlotService attractionSlotService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // UserService 생성에 필요하지만 예약 경로에서는 사용하지 않는 의존성이다.
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
                    .email("kan105-" + UUID.randomUUID().toString().substring(0, 12) + "@test.com")
                    .password("test-password").nickname("마감검증회원").build();
            entityManager.persist(user);
            TicketProduct product = new TicketProduct(
                    "종일 이용권", 10000, "잠금 대기 후 예약 마감 검증",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            Order order = Order.create(user, 10000, SLOT_START.toLocalDate());
            OrderItem item = OrderItem.create(order, product, 1);
            order.getOrderItems().add(item);
            entityManager.persist(order);
            Ticket ticket = new Ticket(SLOT_START.toLocalDate(), item);
            ticket.entryTicket(SLOT_START.withHour(10).atZone(KstTime.ZONE).toInstant());
            entityManager.persist(ticket);
            Attraction attraction = Attraction.create(
                    "kan105-" + UUID.randomUUID(), "마감 테스트 시설", "A구역", 30, 5
            );
            entityManager.persist(attraction);
            AttractionSlot slot = AttractionSlot.create(attraction, SLOT_START, 5);
            entityManager.persist(slot);
            return new Fixture(user.getId(), product.getId(), order.getId(), item.getId(),
                    ticket.getId(), attraction.getId(), slot.getId());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 수정 전 코드에서 예약이 저장되어도 이번 fixture의 데이터만 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM Reservation r WHERE r.attractionSlot.id = :id")
                    .setParameter("id", fixture.slotId()).executeUpdate();
            entityManager.remove(entityManager.find(Ticket.class, fixture.ticketId()));
            entityManager.remove(entityManager.find(AttractionSlot.class, fixture.slotId()));
            entityManager.remove(entityManager.find(Attraction.class, fixture.attractionId()));
            entityManager.remove(entityManager.find(OrderItem.class, fixture.itemId()));
            entityManager.remove(entityManager.find(Order.class, fixture.orderId()));
            entityManager.remove(entityManager.find(TicketProduct.class, fixture.productId()));
            entityManager.remove(entityManager.find(User.class, fixture.userId()));
        });
    }

    @Test
    @DisplayName("마감 직전에 시작했어도 회원 잠금을 기다리는 동안 마감이 지나면 예약과 정원 변경을 거부한다")
    void createReservation_DeadlinePassesWhileWaitingForUserLock_RejectsWithoutChanges() throws Exception {
        // given
        Snapshot before = readSnapshot();

        // when
        ReservationResult result = reserveAfterUserLockWait(AFTER_DEADLINE);

        // then
        assertDeadlineRejectedWithoutChanges(result, before);
    }

    @Test
    @DisplayName("회원 잠금을 얻었어도 슬롯 잠금을 기다리는 동안 마감이 지나면 예약과 정원 변경을 거부한다")
    void createReservation_DeadlinePassesWhileWaitingForSlotLock_RejectsWithoutChanges() throws Exception {
        // given
        Snapshot before = readSnapshot();

        // when
        ReservationResult result = reserveAfterLockWait(AttractionSlot.class, fixture.slotId(), AFTER_DEADLINE);

        // then
        assertDeadlineRejectedWithoutChanges(result, before);
    }

    @Test
    @DisplayName("회원·슬롯 잠금을 얻었어도 어트랙션 잠금을 기다리는 동안 마감이 지나면 예약과 정원 변경을 거부한다")
    void createReservation_DeadlinePassesWhileWaitingForAttractionLock_RejectsWithoutChanges() throws Exception {
        // given
        Snapshot before = readSnapshot();

        // when
        ReservationResult result = reserveAfterLockWait(Attraction.class, fixture.attractionId(), AFTER_DEADLINE);

        // then
        assertDeadlineRejectedWithoutChanges(result, before);
    }

    private void assertDeadlineRejectedWithoutChanges(ReservationResult result, Snapshot before) {
        Snapshot after = readSnapshot();
        assertSoftly(softly -> {
            softly.assertThat(result.response()).as("잠금 획득 시 마감이 지났으므로 성공 응답이 없어야 한다").isNull();
            softly.assertThat(result.failure()).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_DEADLINE_PASSED));
            softly.assertThat(after.reservationCount()).as("예약 저장 건수").isZero();
            softly.assertThat(after.reservedCount()).as("슬롯 예약 인원").isEqualTo(before.reservedCount());
            softly.assertThat(after.remainingCount()).as("슬롯 잔여 정원").isEqualTo(before.remainingCount());
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    @DisplayName("회원 잠금을 기다린 뒤에도 마감 이전이거나 마감 정각이면 예약을 생성하고 정원을 한 자리 사용한다")
    void createReservation_UserLockReleasedBeforeOrAtDeadline_SavesReservation(int secondsFromDeadline) throws Exception {
        // given
        Snapshot before = readSnapshot();
        LocalDateTime releasedAt = DEADLINE.plusSeconds(secondsFromDeadline);

        // when
        ReservationResult result = reserveAfterUserLockWait(releasedAt);

        // then
        assertThat(result.failure()).as("마감 이전·정각에는 업무 오류가 없어야 한다").isNull();
        assertThat(result.response()).isNotNull();
        assertThat(result.response().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(result.response().slotId()).isEqualTo(fixture.slotId());
        assertThat(result.response().ticketId()).isEqualTo(fixture.ticketId());
        Snapshot after = readSnapshot();
        assertSoftly(softly -> {
            softly.assertThat(after.reservationCount()).as("예약 저장 건수").isEqualTo(1);
            softly.assertThat(after.reservedCount()).as("슬롯 예약 인원").isEqualTo(before.reservedCount() + 1);
            softly.assertThat(after.remainingCount()).as("슬롯 잔여 정원").isEqualTo(before.remainingCount() - 1);
        });
    }

    private ReservationResult reserveAfterUserLockWait(LocalDateTime releasedAt) throws Exception {
        return reserveAfterLockWait(User.class, fixture.userId(), releasedAt);
    }

    private ReservationResult reserveAfterLockWait(Class<?> lockedType, Long lockedId, LocalDateTime releasedAt)
            throws Exception {
        AtomicReference<LocalDateTime> serverTime = new AtomicReference<>(BEFORE_DEADLINE);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch lookupStarted = new CountDownLatch(1);
        CreateReservationRequest request = new CreateReservationRequest(fixture.ticketId(), fixture.slotId());
        notifyBeforeLockLookup(lockedType, lookupStarted);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // 이 행만 잠가 예약 요청이 어느 잠금에서 대기하는지 구분한다.
                entityManager.find(lockedType, lockedId, LockModeType.PESSIMISTIC_WRITE);
                rowLocked.countDown();
                awaitSignal(releaseLock);
            }));
            assertThat(rowLocked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ReservationResult> reservation = executor.submit(() -> {
                // static mock은 예약 작업 스레드에만 적용하고, now 외의 KST 변환은 실제 동작을 유지한다.
                try (MockedStatic<KstTime> timeMock = mockStatic(KstTime.class, CALLS_REAL_METHODS)) {
                    timeMock.when(KstTime::now).thenAnswer(invocation -> serverTime.get());
                    try {
                        return new ReservationResult(reservationService.createReservation(fixture.userId(), request), null);
                    } catch (RuntimeException exception) {
                        return new ReservationResult(null, exception);
                    }
                }
            });
            assertThat(lookupStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // 요청의 미완료를 확인하며, MySQL 잠금 대기 테이블을 직접 조회하는 검증은 아니다.
            assertThatThrownBy(() -> reservation.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            serverTime.set(releasedAt);
            releaseLock.countDown();
            holder.get(20, TimeUnit.SECONDS);
            return reservation.get(20, TimeUnit.SECONDS);
        } finally {
            releaseLock.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private void notifyBeforeLockLookup(Class<?> lockedType, CountDownLatch lookupStarted) {
        // Spy는 해당 조회에 도달한 신호만 보내고 실제 Repository의 잠금 조회를 그대로 실행한다.
        if (lockedType == User.class) {
            doAnswer(invocation -> {
                lookupStarted.countDown();
                return invocation.callRealMethod();
            }).when(userService).findByUserId(fixture.userId());
        } else if (lockedType == AttractionSlot.class) {
            doAnswer(invocation -> {
                lookupStarted.countDown();
                return invocation.callRealMethod();
            }).when(attractionSlotService).findSlot(fixture.slotId());
        } else if (lockedType == Attraction.class) {
            doAnswer(invocation -> {
                lookupStarted.countDown();
                return invocation.callRealMethod();
            }).when(attractionSlotService).findAttractionByIdForUpdate(fixture.attractionId());
        } else {
            throw new IllegalArgumentException("지원하지 않는 잠금 대상: " + lockedType.getName());
        }
    }

    private Snapshot readSnapshot() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            AttractionSlot slot = entityManager.find(AttractionSlot.class, fixture.slotId());
            long count = entityManager.createQuery(
                            "SELECT COUNT(r) FROM Reservation r WHERE r.attractionSlot.id = :id", Long.class)
                    .setParameter("id", fixture.slotId()).getSingleResult();
            return new Snapshot(count, slot.getReservedCount(), slot.getRemainingCount());
        }));
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("행 잠금 해제 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("행 잠금 유지 중 인터럽트 발생", exception);
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId, Long itemId,
                           Long ticketId, Long attractionId, Long slotId) {
    }

    private record ReservationResult(CreateReservationResponse response, RuntimeException failure) {
    }

    private record Snapshot(long reservationCount, int reservedCount, int remainingCount) {
    }
}
