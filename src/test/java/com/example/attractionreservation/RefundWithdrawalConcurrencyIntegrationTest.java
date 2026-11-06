package com.example.attractionreservation;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundService;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.domain.user.service.UserWithdrawalService;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 서비스·Repository·MySQL 트랜잭션을 사용한다. PG 호출·환불 완료 처리는 실행하지 않는다.
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, QuerydslConfig.class, RefundService.class, TicketService.class,
        UserWithdrawalService.class, RefundWithdrawalConcurrencyIntegrationTest.MySqlConfig.class})
class RefundWithdrawalConcurrencyIntegrationTest {

    @Autowired
    private RefundService refundService;

    @Autowired
    private UserWithdrawalService withdrawalService;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private UserRepository userRepository;

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
            User user = User.builder()
                    .email("kan120-" + UUID.randomUUID().toString().replace("-", "") + "@test.com")
                    .password("test-password-hash").nickname("환불탈퇴동시성").build();
            entityManager.persist(user);
            TicketProduct product = new TicketProduct("종일권", 10000, "환불과 탈퇴 경합 검증",
                    TicketType.ALL_DAY, 99, TicketProductStatus.ON_SALE);
            entityManager.persist(product);

            // 이용일이 지나도 UNUSED로 남은 티켓에 관리자가 기한 제한 없이 환불을 요청하는 경우다.
            // 미래 UNUSED 티켓을 쓰면 기존 미사용 티켓 검사만으로 거부돼 환불 검사 경합이 가려진다.
            LocalDate visitDate = LocalDate.now(KstTime.ZONE).minusDays(1);
            Order order = Order.create(user, 10000, visitDate);
            order.addOrderItem(product, 1);
            order.markAsPaid();
            entityManager.persist(order);
            Payment payment = Payment.create(order);
            payment.markAsPaid(Instant.now());
            entityManager.persist(payment);
            Ticket ticket = new Ticket(visitDate, order.getOrderItems().get(0));
            entityManager.persist(ticket);
            entityManager.flush();
            return new Fixture(user.getId(), product.getId(), order.getId(),
                    payment.getId(), payment.getPgId(), ticket.getId());
        }));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        // 다른 테스트의 데이터를 지우지 않고 이번 fixture만 외래 키 순서에 맞춰 정리한다.
        transactionTemplate.executeWithoutResult(transaction -> {
            entityManager.createQuery("DELETE FROM RefundItem i WHERE i.refund.payment.id = :id")
                    .setParameter("id", fixture.paymentId()).executeUpdate();
            entityManager.createQuery("DELETE FROM Refund r WHERE r.payment.id = :id")
                    .setParameter("id", fixture.paymentId()).executeUpdate();
            entityManager.remove(entityManager.find(Ticket.class, fixture.ticketId()));
            entityManager.remove(entityManager.find(Payment.class, fixture.paymentId()));
            entityManager.flush();
            entityManager.remove(entityManager.find(Order.class, fixture.orderId()));
            entityManager.flush();
            entityManager.remove(entityManager.find(TicketProduct.class, fixture.productId()));
            entityManager.remove(entityManager.find(User.class, fixture.userId()));
        });
    }

    @Test
    @DisplayName("탈퇴 검사 중 관리자 환불 요청이 커밋되면 미완료 환불을 놓치지 않고 탈퇴를 보류한다")
    void withdraw_overlapsAdminRefundCommit_rejectsWithdrawal() throws Exception {
        // given
        CountDownLatch refundWritten = new CountDownLatch(1);
        CountDownLatch allowRefundCommit = new CountDownLatch(1);
        CountDownLatch unusedTicketChecked = new CountDownLatch(1);
        CountDownLatch allowRefundCheck = new CountDownLatch(1);
        CountDownLatch withdrawalStarted = new CountDownLatch(1);
        AtomicReference<Thread> withdrawalThread = new AtomicReference<>();

        // 실제 미사용 티켓 쿼리를 실행한 뒤 탈퇴 스레드만 멈춘다. 조회 결과를 mock하지 않는다.
        var advisor = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (Thread.currentThread() == withdrawalThread.get()
                    && invocation.getMethod().getName().equals(
                    "existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual")) {
                assertThat(result).isEqualTo(false);
                unusedTicketChecked.countDown();
                await(allowRefundCheck, "탈퇴 환불 검사 재개");
            }
            return result;
        });
        Advised repositoryProxy = (Advised) ticketRepository;
        repositoryProxy.addAdvisor(0, advisor);
        var executor = Executors.newFixedThreadPool(2);

        try {
            // when: 환불 요청을 먼저 저장하되 아직 커밋하지 않아 탈퇴와 실제로 겹치게 한다.
            var refundFuture = executor.submit(() -> transactionTemplate.execute(transaction -> {
                Long refundId = refundService.createAdminFullRefund(
                        fixture.pgId(), "관리자 환불", Instant.now());
                entityManager.flush();
                refundWritten.countDown();
                await(allowRefundCommit, "환불 요청 커밋");
                return refundId;
            }));
            await(refundWritten, "환불 요청 저장");
            var withdrawalFuture = executor.submit(() -> {
                withdrawalThread.set(Thread.currentThread());
                withdrawalStarted.countDown();
                try {
                    withdrawalService.withdraw(fixture.userId());
                    return (ErrorCode) null;
                } catch (BusinessException exception) {
                    return exception.getErrorCode();
                }
            });

            // 현재 구조에서는 먼저 미사용 티켓 조회까지 진행할 수 있다.
            // 향후 공통 잠금으로 직렬화되면 기다릴 수 있으므로, 시간 초과 자체는 실패로 보지 않는다.
            await(withdrawalStarted, "탈퇴 작업 시작");
            unusedTicketChecked.await(500, TimeUnit.MILLISECONDS);
            allowRefundCommit.countDown();
            Long refundId = refundFuture.get(10, TimeUnit.SECONDS);
            await(unusedTicketChecked, "미사용 티켓 검사 완료");
            allowRefundCheck.countDown();
            ErrorCode withdrawalError = withdrawalFuture.get(10, TimeUnit.SECONDS);

            // then: 환불 커밋 후 환불 검사를 재개했으므로 탈퇴 성공은 허용하지 않는다.
            // DB 오류·락 시간 초과는 위 Future.get에서 실패하며 업무상 거부로 인정하지 않는다.
            assertThat(withdrawalError).isEqualTo(ErrorCode.USER_WITHDRAWAL_INCOMPLETE_REFUND);
            transactionTemplate.executeWithoutResult(transaction -> {
                User user = entityManager.find(User.class, fixture.userId());
                assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
                assertThat(user.getWithdrawnAt()).isNull();
                assertThat(entityManager.find(Refund.class, refundId).getStatus())
                        .isEqualTo(RefundStatus.REQUESTED);
                assertThat(entityManager.find(Ticket.class, fixture.ticketId()).getStatus())
                        .isEqualTo(TicketStatus.REFUNDED);
            });
        } finally {
            allowRefundCommit.countDown();
            allowRefundCheck.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("동시 작업 종료 후 fixture 정리").isTrue();
            } finally {
                repositoryProxy.removeAdvisor(advisor);
            }
        }
    }

    @ParameterizedTest(name = "전체 환불 여부: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("탈퇴가 먼저 커밋되면 회원 잠금을 기다리던 관리자 전체·부분 환불을 거부한다")
    void adminRefund_withdrawalCommitsFirst_rejectsRefund(boolean fullRefund) throws Exception {
        // given
        CountDownLatch withdrawalWritten = new CountDownLatch(1);
        CountDownLatch allowWithdrawalCommit = new CountDownLatch(1);
        CountDownLatch refundLockRequested = new CountDownLatch(1);
        AtomicReference<Thread> refundThread = new AtomicReference<>();

        // 환불 스레드가 실제 회원 잠금 쿼리를 호출하기 직전임을 확인한다.
        // 결과를 바꾸거나 잠금 자체를 대체하지 않는다.
        var advisor = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
            if (Thread.currentThread() == refundThread.get()
                    && invocation.getMethod().getName().equals("findByIdForUpdate")) {
                refundLockRequested.countDown();
            }
            return invocation.proceed();
        });
        Advised repositoryProxy = (Advised) userRepository;
        repositoryProxy.addAdvisor(0, advisor);
        var executor = Executors.newFixedThreadPool(2);

        try {
            // when: 실제 탈퇴를 수행하되 바깥 트랜잭션을 열어 회원 잠금을 유지한다.
            var withdrawalFuture = executor.submit(() -> transactionTemplate.execute(transaction -> {
                withdrawalService.withdraw(fixture.userId());
                entityManager.flush();
                withdrawalWritten.countDown();
                await(allowWithdrawalCommit, "탈퇴 커밋");
                return true;
            }));
            await(withdrawalWritten, "탈퇴 변경 저장");

            var refundFuture = executor.submit(() -> {
                refundThread.set(Thread.currentThread());
                try {
                    if (fullRefund) {
                        refundService.createAdminFullRefund(fixture.pgId(), "관리자 환불", Instant.now());
                    } else {
                        refundService.createAdminPartialRefund(
                                fixture.pgId(), List.of(fixture.ticketId()), "관리자 환불", Instant.now());
                    }
                    return (ErrorCode) null;
                } catch (BusinessException exception) {
                    return exception.getErrorCode();
                }
            });
            await(refundLockRequested, "환불의 회원 잠금 요청");

            // 탈퇴 커밋 전에는 환불 요청이 끝나지 않아야 한다.
            // 이 시간 검사는 DB 잠금 대기 자체의 증명이 아니므로 최종 업무 결과도 함께 검증한다.
            assertThatThrownBy(() -> refundFuture.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowWithdrawalCommit.countDown();
            assertThat(withdrawalFuture.get(10, TimeUnit.SECONDS)).isTrue();
            ErrorCode refundError = refundFuture.get(10, TimeUnit.SECONDS);

            // then: 대기 전의 ACTIVE가 아니라 잠금 획득 후의 WITHDRAWN을 기준으로 거부해야 한다.
            assertThat(refundError).isEqualTo(ErrorCode.REFUND_USER_WITHDRAW);
            transactionTemplate.executeWithoutResult(transaction -> {
                User user = entityManager.find(User.class, fixture.userId());
                assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
                assertThat(user.getWithdrawnAt()).isNotNull();
                Long refundCount = entityManager.createQuery(
                                "SELECT COUNT(r) FROM Refund r WHERE r.payment.id = :paymentId", Long.class)
                        .setParameter("paymentId", fixture.paymentId()).getSingleResult();
                assertThat(refundCount).isZero();
                assertThat(entityManager.find(Ticket.class, fixture.ticketId()).getStatus())
                        .isEqualTo(TicketStatus.UNUSED);
                assertThat(entityManager.find(Payment.class, fixture.paymentId()).getStatus())
                        .isEqualTo(PaymentStatus.PAID);
                assertThat(entityManager.find(Order.class, fixture.orderId()).getStatus())
                        .isEqualTo(OrderStatus.COMPLETED);
                assertThat(entityManager.find(TicketProduct.class, fixture.productId()).getStock())
                        .isEqualTo(99);
            });
        } finally {
            allowWithdrawalCommit.countDown();
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                        .as("동시 작업 종료 후 fixture 정리").isTrue();
            } finally {
                repositoryProxy.removeAdvisor(advisor);
            }
        }
    }

    private static void await(CountDownLatch latch, String step) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(step + " 신호를 받지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(step + " 대기 중 중단됐습니다.", exception);
        }
    }

    private record Fixture(Long userId, Long productId, Long orderId,
                           Long paymentId, String pgId, Long ticketId) {
    }
}
