package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryRequest;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
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
import org.mockito.MockedStatic;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

// 실제 MySQL에서 선행 입장의 commit까지 후행 입장이 기다리고, 중복 입장을 거절하는지 검증한다.
// Service와 Repository는 mock으로 대체하지 않는다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, TicketService.class, TicketExpirationService.class,
        TicketStateConcurrencyIntegrationTest.MySqlConfig.class})
class TicketStateConcurrencyIntegrationTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 11, 2);
    // static mock 시작 전에 값을 만들고, 두 요청 모두 정상 입장 시간인 KST 14시대로 고정한다.
    private static final Instant FIRST_SCAN_AT = LocalDateTime.of(2026, 11, 2, 14, 0)
            .atZone(KstTime.ZONE).toInstant();
    private static final Instant SECOND_SCAN_AT = FIRST_SCAN_AT.plusSeconds(1);
    private static final Instant CLOSE_AT = VISIT_DATE.atTime(22, 0).atZone(KstTime.ZONE).toInstant();
    private static final Instant BEFORE_CLOSE_AT = CLOSE_AT.minusSeconds(1);

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketExpirationService ticketExpirationService;

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

        @Bean
        Clock clock() {
            return Clock.fixed(CLOSE_AT, KstTime.ZONE);
        }
    }

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        fixture = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = User.builder()
                    .email("kan100-" + UUID.randomUUID().toString().substring(0, 12) + "@test.com")
                    .password("test-password")
                    .nickname("입장동시성")
                    .build();
            entityManager.persist(user);

            TicketProduct product = new TicketProduct(
                    "종일 이용권", 50000, "동시 입장 테스트 상품",
                    TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            Order order = Order.create(user, 50000, VISIT_DATE);
            entityManager.persist(order);
            OrderItem orderItem = OrderItem.create(order, product, 1);
            entityManager.persist(orderItem);
            Ticket ticket = new Ticket(VISIT_DATE, orderItem);
            entityManager.persist(ticket);
            return new Fixture(user.getId(), product.getId(), order.getId(), orderItem.getId(),
                    ticket.getId(), ticket.getCode());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 직접 준비한 fixture만 FK 참조 순서대로 정리한다. 다른 테스트의 데이터는 전체 삭제하지 않는다.
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Ticket t where t.id = :id")
                    .setParameter("id", fixture.ticketId()).executeUpdate();
            entityManager.createQuery("delete from OrderItem i where i.id = :id")
                    .setParameter("id", fixture.orderItemId()).executeUpdate();
            entityManager.createQuery("delete from Order o where o.id = :id")
                    .setParameter("id", fixture.orderId()).executeUpdate();
            entityManager.createQuery("delete from TicketProduct p where p.id = :id")
                    .setParameter("id", fixture.productId()).executeUpdate();
            entityManager.createQuery("delete from User u where u.id = :id")
                    .setParameter("id", fixture.userId()).executeUpdate();
        });
    }

    @Test
    @DisplayName("같은 티켓을 두 번 동시에 스캔하면 한 요청만 입장하고 성공 요청의 입장 시각을 보존한다")
    void createTicketEntry_SameTicketConcurrently_OnlyOneSucceedsAndPreservesEnteredAt() throws Exception {
        // given
        CountDownLatch firstEntryApplied = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondScanStarted = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateTicketEntryResponse> first = executor.submit(() ->
                    scanAndWaitForCommit(FIRST_SCAN_AT, firstEntryApplied, allowFirstCommit));
            assertThat(firstEntryApplied.await(10, TimeUnit.SECONDS))
                    .as("선행 입장이 티켓을 잠그고 상태를 변경한 뒤 commit을 대기해야 한다")
                    .isTrue();
            Future<ScanResult> second = executor.submit(() -> {
                secondScanStarted.countDown();
                return scanAt(SECOND_SCAN_AT);
            });
            assertThat(secondScanStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // 일정 시간 미완료인지 확인하며, DB 내부의 lock wait 정보 자체를 조회하는 것은 아니다.
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .as("선행 transaction이 commit되기 전에는 후행 입장이 완료되면 안 된다")
                    .isInstanceOf(TimeoutException.class);
            allowFirstCommit.countDown();
            CreateTicketEntryResponse firstResponse = first.get(20, TimeUnit.SECONDS);
            List<ScanResult> results = List.of(new ScanResult(firstResponse, null), second.get(20, TimeUnit.SECONDS));

            // then: 각 Service transaction이 끝난 뒤 새 transaction에서 최종 상태를 조회한다.
            List<CreateTicketEntryResponse> successes = results.stream()
                    .filter(result -> result.failure() == null)
                    .map(ScanResult::response)
                    .toList();
            List<RuntimeException> failures = results.stream()
                    .map(ScanResult::failure)
                    .filter(Objects::nonNull)
                    .toList();
            TicketSnapshot snapshot = Objects.requireNonNull(transactionTemplate.execute(status -> {
                Ticket ticket = entityManager.find(Ticket.class, fixture.ticketId());
                return new TicketSnapshot(ticket.getStatus(), ticket.getEnteredAt(), ticket.getEndTime());
            }));

            assertSoftly(softly -> {
                softly.assertThat(successes)
                        .as("동시 입장 성공 수: 실제 성공 요청의 enteredAt=%s",
                                successes.stream().map(CreateTicketEntryResponse::enteredAt).toList())
                        .hasSize(1);
                softly.assertThat(failures).as("중복 입장은 상태 오류로 한 번 거절되어야 한다").hasSize(1);
                softly.assertThat(failures).allSatisfy(failure ->
                        assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS)));
                softly.assertThat(successes).allSatisfy(response -> {
                    assertThat(response.ticketId()).isEqualTo(fixture.ticketId());
                    assertThat(response.status()).isEqualTo(TicketStatus.IN_USE);
                    assertThat(response.enteredAt()).isEqualTo(FIRST_SCAN_AT);
                });
                softly.assertThat(snapshot.status()).isEqualTo(TicketStatus.IN_USE);
                softly.assertThat(snapshot.enteredAt()).isEqualTo(FIRST_SCAN_AT);
                softly.assertThat(snapshot.endTime()).isNull();
                if (successes.size() == 1) {
                    softly.assertThat(snapshot.enteredAt()).isEqualTo(successes.get(0).enteredAt());
                }
            });
        } finally {
            allowFirstCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("테스트 작업은 DB 정리 전에 모두 종료되어야 한다").isTrue();
            }
        }
    }

    @Test
    @DisplayName("폐장 직전 입장이 먼저 잠근 티켓은 자동 종료 후 USED가 되고 입장 시각과 폐장 시각을 보존한다")
    void expireTickets_EntryBeforeCloseCommitsFirst_PreservesUsedStateAndTimestamps() throws Exception {
        // given
        CountDownLatch entryApplied = new CountDownLatch(1);
        CountDownLatch allowEntryCommit = new CountDownLatch(1);
        CountDownLatch expirationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateTicketEntryResponse> entryResult = executor.submit(() ->
                    scanAndWaitForCommit(BEFORE_CLOSE_AT, entryApplied, allowEntryCommit));
            assertThat(entryApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> expirationResult = executor.submit(() -> {
                expirationStarted.countDown();
                return ticketExpirationService.expireTickets(100);
            });
            assertThat(expirationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> expirationResult.get(500, TimeUnit.MILLISECONDS))
                    .as("자동 종료는 선행 입장 transaction의 commit까지 대기해야 한다")
                    .isInstanceOf(TimeoutException.class);

            allowEntryCommit.countDown();
            CreateTicketEntryResponse entryResponse = entryResult.get(20, TimeUnit.SECONDS);
            int expiredCount = expirationResult.get(20, TimeUnit.SECONDS);

            // then
            TicketSnapshot afterExpiration = readTicketSnapshot();
            assertThat(entryResponse.enteredAt()).isEqualTo(BEFORE_CLOSE_AT);
            assertThat(expiredCount).isEqualTo(1);
            assertSoftly(softly -> {
                softly.assertThat(afterExpiration.status())
                        .as("폐장 전에 입장에 성공한 티켓은 EXPIRED가 아닌 USED여야 한다")
                        .isEqualTo(TicketStatus.USED);
                softly.assertThat(afterExpiration.enteredAt())
                        .as("자동 종료가 먼저 읽은 값으로 정상 입장의 enteredAt을 지우면 안 된다")
                        .isEqualTo(BEFORE_CLOSE_AT);
                softly.assertThat(afterExpiration.endTime()).isEqualTo(CLOSE_AT);
            });
        } finally {
            allowEntryCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("테스트 작업은 DB 정리 전에 모두 종료되어야 한다").isTrue();
            }
        }
    }

    @Test
    @DisplayName("자동 종료가 먼저 잠근 티켓은 입장이 commit까지 기다린 뒤 거절되고 만료 상태와 종료 시각을 유지한다")
    void createTicketEntry_ExpirationLocksFirst_RejectsEntryAndPreservesExpiration() throws Exception {
        // given
        CountDownLatch expirationApplied = new CountDownLatch(1);
        CountDownLatch allowExpirationCommit = new CountDownLatch(1);
        CountDownLatch entryStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<Integer> expirationResult = executor.submit(() -> transactionTemplate.execute(status -> {
                // 실제 종료 Service가 획득한 잠금을 유지하고 commit만 지연한다.
                int count = ticketExpirationService.expireTickets(100);
                expirationApplied.countDown();
                awaitSignal(allowExpirationCommit);
                return count;
            }));
            assertThat(expirationApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ScanResult> entryResult = executor.submit(() -> {
                entryStarted.countDown();
                return scanAt(CLOSE_AT);
            });
            assertThat(entryStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> entryResult.get(500, TimeUnit.MILLISECONDS))
                    .as("자동 종료 transaction이 commit되기 전에는 후행 입장이 완료되면 안 된다")
                    .isInstanceOf(TimeoutException.class);
            allowExpirationCommit.countDown();
            assertThat(expirationResult.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            ScanResult entry = entryResult.get(20, TimeUnit.SECONDS);

            // then
            assertThat(entry.response()).isNull();
            assertThat(entry.failure()).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS));
            TicketSnapshot snapshot = readTicketSnapshot();
            assertThat(snapshot.status()).isEqualTo(TicketStatus.EXPIRED);
            assertThat(snapshot.enteredAt()).isNull();
            assertThat(snapshot.endTime()).isEqualTo(CLOSE_AT);
            assertThat(ticketExpirationService.expireTickets(100)).isZero();
            assertThat(readTicketSnapshot()).isEqualTo(snapshot);
        } finally {
            allowExpirationCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("테스트 작업은 DB 정리 전에 모두 종료되어야 한다").isTrue();
            }
        }
    }

    @Test
    @DisplayName("환불용 잠금으로 환불 상태를 변경 중이면 자동 종료는 commit 후 대상을 제외하고 환불 상태를 유지한다")
    void expireTickets_RefundLocksFirst_SkipsRefundedTicketAndPreservesTimestamps() throws Exception {
        // given
        CountDownLatch refundApplied = new CountDownLatch(1);
        CountDownLatch allowRefundCommit = new CountDownLatch(1);
        CountDownLatch expirationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<?> refundResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // 실제 환불 경로의 티켓 잠금·상태 변경 부분만 검증한다.
                // 환불 API의 날짜 검증이나 결제·PG 처리는 호출하지 않으므로 정상 환불 업무 전체의 재현은 아니다.
                List<Ticket> tickets = ticketService.findRefundTicketsForUpdate(
                        List.of(fixture.orderItemId()), List.of(fixture.ticketId())
                );
                assertThat(tickets).singleElement().satisfies(ticket -> {
                    assertThat(ticket.getStatus()).isEqualTo(TicketStatus.UNUSED);
                    ticket.refund();
                });
                refundApplied.countDown();
                awaitSignal(allowRefundCommit);
            }));
            assertThat(refundApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> expirationResult = executor.submit(() -> {
                expirationStarted.countDown();
                return ticketExpirationService.expireTickets(100);
            });
            assertThat(expirationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> expirationResult.get(500, TimeUnit.MILLISECONDS))
                    .as("자동 종료는 선행 티켓 환불 transaction의 commit까지 대기해야 한다")
                    .isInstanceOf(TimeoutException.class);
            allowRefundCommit.countDown();
            refundResult.get(20, TimeUnit.SECONDS);
            int expiredCount = expirationResult.get(20, TimeUnit.SECONDS);

            // then
            assertThat(expiredCount).isZero();
            TicketSnapshot snapshot = readTicketSnapshot();
            assertThat(snapshot.status()).isEqualTo(TicketStatus.REFUNDED);
            assertThat(snapshot.enteredAt()).isNull();
            assertThat(snapshot.endTime()).isNull();
            assertThat(ticketExpirationService.expireTickets(100)).isZero();
            assertThat(readTicketSnapshot()).isEqualTo(snapshot);
        } finally {
            allowRefundCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("테스트 작업은 DB 정리 전에 모두 종료되어야 한다").isTrue();
            }
        }
    }

    private TicketSnapshot readTicketSnapshot() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            Ticket ticket = entityManager.find(Ticket.class, fixture.ticketId());
            return new TicketSnapshot(ticket.getStatus(), ticket.getEnteredAt(), ticket.getEndTime());
        }));
    }

    private CreateTicketEntryResponse scanAndWaitForCommit(
            Instant now, CountDownLatch entryApplied, CountDownLatch allowCommit
    ) {
        try (MockedStatic<Instant> instantMock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            instantMock.when(Instant::now).thenReturn(now);
            return Objects.requireNonNull(transactionTemplate.execute(status -> {
                // Service가 참여한 transaction의 commit만 지연한다. 테스트에서 별도 잠금이나 flush는 추가하지 않는다.
                CreateTicketEntryResponse response = ticketService.createTicketEntry(
                        new CreateTicketEntryRequest(fixture.ticketCode())
                );
                entryApplied.countDown();
                awaitSignal(allowCommit);
                return response;
            }));
        }
    }

    private ScanResult scanAt(Instant now) {
        // Mockito static mock은 스레드별로 적용한다. Instant의 다른 메서드는 실제 동작을 유지한다.
        try (MockedStatic<Instant> instantMock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            instantMock.when(Instant::now).thenReturn(now);
            try {
                CreateTicketEntryResponse response = ticketService.createTicketEntry(
                        new CreateTicketEntryRequest(fixture.ticketCode())
                );
                return new ScanResult(response, null);
            } catch (RuntimeException exception) {
                return new ScanResult(null, exception);
            }
        }
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("입장 처리 재개 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("입장 처리 대기 중 인터럽트 발생", exception);
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId, Long orderItemId,
                           Long ticketId, String ticketCode) {
    }

    private record ScanResult(CreateTicketEntryResponse response, RuntimeException failure) {
    }

    private record TicketSnapshot(TicketStatus status, Instant enteredAt, Instant endTime) {
    }
}
