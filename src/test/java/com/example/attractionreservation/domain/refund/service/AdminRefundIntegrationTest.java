package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

// 환불 요청 저장과 티켓 상태 변경의 실제 DB 정합성을 검증한다. PG 호출·완료·재고 복구는 실행하지 않는다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, RefundService.class, TicketService.class,
        AdminRefundIntegrationTest.MySqlConfig.class})
class AdminRefundIntegrationTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 11, 5);
    private static final Instant BEFORE_VISIT = Instant.parse("2026-11-04T01:00:00Z");
    private static final Instant AFTER_VISIT = Instant.parse("2026-11-06T01:00:00Z");

    @Autowired
    private RefundService refundService;

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
        fixture = Objects.requireNonNull(transactionTemplate.execute(status -> {
            User user = User.builder().email("kan104-" + UUID.randomUUID().toString().substring(0, 12) + "@test.com")
                    .password("password").nickname("환불회원").build();
            entityManager.persist(user);
            TicketProduct product = new TicketProduct(
                    "종일 이용권", 10000, "관리자 환불 검증", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            Order order = Order.create(user, 30000, VISIT_DATE);
            OrderItem item = OrderItem.create(order, product, 3);
            order.getOrderItems().add(item);
            entityManager.persist(order);
            Payment payment = Payment.create(order);
            payment.markAsPaid(BEFORE_VISIT);
            entityManager.persist(payment);
            List<Long> ticketIds = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                Ticket ticket = new Ticket(VISIT_DATE, item);
                entityManager.persist(ticket);
                ticketIds.add(ticket.getId());
            }
            return new Fixture(user.getId(), product.getId(), order.getId(), item.getId(),
                    payment.getId(), payment.getPgId(), List.copyOf(ticketIds));
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("DELETE FROM RefundItem i WHERE i.refund.payment.id = :id")
                    .setParameter("id", fixture.paymentId()).executeUpdate();
            entityManager.createQuery("DELETE FROM Refund r WHERE r.payment.id = :id")
                    .setParameter("id", fixture.paymentId()).executeUpdate();
            entityManager.createQuery("DELETE FROM Ticket t WHERE t.orderItem.id = :id")
                    .setParameter("id", fixture.itemId()).executeUpdate();
            entityManager.createQuery("DELETE FROM Payment p WHERE p.id = :id")
                    .setParameter("id", fixture.paymentId()).executeUpdate();
            entityManager.createQuery("DELETE FROM OrderItem i WHERE i.id = :id")
                    .setParameter("id", fixture.itemId()).executeUpdate();
            entityManager.createQuery("DELETE FROM Order o WHERE o.id = :id")
                    .setParameter("id", fixture.orderId()).executeUpdate();
            entityManager.createQuery("DELETE FROM TicketProduct p WHERE p.id = :id")
                    .setParameter("id", fixture.productId()).executeUpdate();
            entityManager.createQuery("DELETE FROM User u WHERE u.id = :id")
                    .setParameter("id", fixture.userId()).executeUpdate();
        });
    }

    @Test
    @DisplayName("관리자 전체 환불은 기한이 지나도 실제 DB에서 미사용 티켓만 요청에 포함한다")
    void createAdminFullRefund_MixedStatusesAfterDeadline_RefundsOnlyUnused() {
        // given
        setTicketStatus(1, TicketStatus.IN_USE);
        setTicketStatus(2, TicketStatus.EXPIRED);

        // when
        Long refundId = refundService.createAdminFullRefund(fixture.pgId(), "관리자 환불", AFTER_VISIT);

        // then
        Snapshot snapshot = readSnapshot();
        assertThat(refundId).isNotNull();
        assertThat(snapshot.refundCount()).isEqualTo(1);
        assertThat(snapshot.refundAmount()).isEqualTo(10000);
        assertThat(snapshot.refundedQuantity()).isEqualTo(1);
        assertThat(snapshot.ticketStatuses()).containsExactly(TicketStatus.REFUNDED, TicketStatus.IN_USE, TicketStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_USE", "USED", "EXPIRED", "REFUNDED"})
    @DisplayName("관리자 부분 환불에 환불 불가 티켓이 섞이면 앞선 티켓 변경도 실제 DB에서 rollback된다")
    void createAdminPartialRefund_ContainsInvalidTicket_RollsBackAllChanges(TicketStatus invalidStatus) {
        // given
        setTicketStatus(1, invalidStatus);
        Snapshot before = readSnapshot();

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(
                fixture.pgId(), fixture.ticketIds().subList(0, 2), "관리자 환불", AFTER_VISIT))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TICKET_NOT_REFUNDABLE);
        assertThat(readSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("사용자와 관리자 중 어느 요청이 먼저여도 같은 티켓 환불은 한 번만 저장된다")
    void createRefund_UserAndAdminSameTicket_OnlyOneRequestCommits(boolean adminFirst) throws Exception {
        // given
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<Long> first = executor.submit(() -> transactionTemplate.execute(status -> {
                Long refundId = requestPartialRefund(adminFirst);
                entityManager.flush();
                firstApplied.countDown();
                awaitSignal(allowCommit);
                return refundId;
            }));
            assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> second = executor.submit(() -> {
                secondStarted.countDown();
                try {
                    return requestPartialRefund(!adminFirst);
                } catch (BusinessException exception) {
                    return exception;
                }
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            // 일정 시간 후행 요청이 완료되지 않는지 확인한다. DB 잠금 대기 테이블을 직접 조회하지는 않는다.
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            allowCommit.countDown();

            // then
            assertThat(first.get(20, TimeUnit.SECONDS)).isNotNull();
            assertThat(second.get(20, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class, exception ->
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_NOT_REFUNDABLE));
            Snapshot snapshot = readSnapshot();
            assertThat(snapshot.refundCount()).isEqualTo(1);
            assertThat(snapshot.refundAmount()).isEqualTo(10000);
            assertThat(snapshot.refundedQuantity()).isEqualTo(1);
            assertThat(snapshot.ticketStatuses()).containsExactly(TicketStatus.REFUNDED, TicketStatus.UNUSED, TicketStatus.UNUSED);
        } finally {
            allowCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private Long requestPartialRefund(boolean admin) {
        List<Long> ids = List.of(fixture.ticketIds().get(0));
        return admin
                ? refundService.createAdminPartialRefund(fixture.pgId(), ids, "관리자 환불", BEFORE_VISIT)
                : refundService.createRefund(fixture.userId(), fixture.pgId(), ids, "사용자 환불", BEFORE_VISIT);
    }

    private void setTicketStatus(int index, TicketStatus ticketStatus) {
        // 환불 검증용 fixture의 상태만 설정한다. 입장·자동 종료 동작 자체를 검증하는 테스트는 아니다.
        transactionTemplate.executeWithoutResult(status -> ReflectionTestUtils.setField(
                entityManager.find(Ticket.class, fixture.ticketIds().get(index)), "status", ticketStatus
        ));
    }

    private Snapshot readSnapshot() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            List<Refund> refunds = entityManager.createQuery(
                            "SELECT r FROM Refund r WHERE r.payment.id = :id", Refund.class)
                    .setParameter("id", fixture.paymentId()).getResultList();
            long quantity = entityManager.createQuery(
                            "SELECT COALESCE(SUM(i.quantity), 0) FROM RefundItem i WHERE i.refund.payment.id = :id", Long.class)
                    .setParameter("id", fixture.paymentId()).getSingleResult();
            List<TicketStatus> statuses = fixture.ticketIds().stream()
                    .map(id -> entityManager.find(Ticket.class, id).getStatus()).toList();
            return new Snapshot(refunds.size(), refunds.stream().mapToInt(Refund::getRefundAmount).sum(), quantity, statuses);
        }));
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("선행 환불 commit 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("환불 대기 중 인터럽트 발생", exception);
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId, Long itemId, Long paymentId,
                           String pgId, List<Long> ticketIds) {
    }

    private record Snapshot(long refundCount, int refundAmount, long refundedQuantity, List<TicketStatus> ticketStatuses) {
    }
}
