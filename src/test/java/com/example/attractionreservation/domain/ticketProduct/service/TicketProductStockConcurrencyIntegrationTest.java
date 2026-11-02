package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
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

// 실제 MySQL에서 삭제와 주문의 두 선행 순서를 검증한다. Service와 Repository는 mock으로 대체하지 않는다.
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class TicketProductStockConcurrencyIntegrationTest {

    @Autowired
    private TicketProductService ticketProductService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("kan95-" + UUID.randomUUID().toString().substring(0, 12) + "@test.com")
                    .password("test-password")
                    .nickname("재고동시성")
                    .build();
            entityManager.persist(user);
            userId = user.getId();

            TicketProduct product = new TicketProduct(
                    "재고 경쟁 테스트 상품", 50000, "관리자 삭제와 주문 경쟁 재현",
                    TicketType.ALL_DAY, 10, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            productId = product.getId();
        });
    }

    @AfterEach
    void tearDown() {
        // 다른 테스트의 데이터를 전체 삭제하지 않고, 이번 회원과 상품에 속한 데이터만 정리한다.
        transactionTemplate.executeWithoutResult(status -> {
            if (userId != null) {
                entityManager.createQuery("""
                                delete from Payment p where p.order.id in
                                (select o.id from Order o where o.user.id = :userId)
                                """)
                        .setParameter("userId", userId).executeUpdate();
                entityManager.createQuery("""
                                delete from OrderItem i where i.order.id in
                                (select o.id from Order o where o.user.id = :userId)
                                """)
                        .setParameter("userId", userId).executeUpdate();
                entityManager.createQuery("delete from Order o where o.user.id = :userId")
                        .setParameter("userId", userId).executeUpdate();
            }
            if (productId != null) {
                entityManager.createQuery("delete from TicketProduct p where p.id = :productId")
                        .setParameter("productId", productId).executeUpdate();
            }
            if (userId != null) {
                entityManager.createQuery("delete from User u where u.id = :userId")
                        .setParameter("userId", userId).executeUpdate();
            }
        });
    }

    @Test
    @DisplayName("삭제가 상품을 먼저 잠그면 주문은 commit까지 기다린 뒤 판매 불가로 거절되고 재고는 유지된다")
    void deleteTicketProduct_DeleteLocksFirst_RejectsOrderAndPreservesStock() throws Exception {
        // given
        CountDownLatch deleteApplied = new CountDownLatch(1);
        CountDownLatch allowDeleteCommit = new CountDownLatch(1);
        CountDownLatch orderStarted = new CountDownLatch(1);
        CreateOrderRequest request = createOrderRequest();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<?> deleteResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // Service는 이 transaction에 참여한다. 여기서 별도 잠금 조회나 flush를 추가하지 않는다.
                ticketProductService.deleteTicketProduct(productId);
                deleteApplied.countDown();
                awaitSignal(allowDeleteCommit);
            }));
            assertThat(deleteApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<CreateOrderResponse> orderResult = executor.submit(() -> {
                orderStarted.countDown();
                return orderService.createOrder(userId, request);
            });
            assertThat(orderStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(orderResult);
            allowDeleteCommit.countDown();
            deleteResult.get(20, TimeUnit.SECONDS);

            // then
            assertThatThrownBy(() -> orderResult.get(20, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOfSatisfying(
                                    BusinessException.class, businessException ->
                                            assertThat(businessException.getErrorCode())
                                                    .isEqualTo(ErrorCode.ORDER_PRODUCT_NOT_ON_SALE)));
            StockSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(snapshot.status()).isEqualTo(TicketProductStatus.DELETED);
                softly.assertThat(snapshot.stock()).isEqualTo(10);
                softly.assertThat(snapshot.orderCount()).isZero();
                softly.assertThat(snapshot.orderedQuantity()).isZero();
            });
        } finally {
            allowDeleteCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("주문이 상품을 먼저 잠그면 삭제는 주문 commit 후 진행하며 최종 재고는 8개를 유지한다")
    void deleteTicketProduct_OrderLocksFirst_PreservesDecreasedStock() throws Exception {
        // given
        CountDownLatch orderApplied = new CountDownLatch(1);
        CountDownLatch allowOrderCommit = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CreateOrderRequest request = createOrderRequest();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateOrderResponse> orderResult = executor.submit(() -> transactionTemplate.execute(status -> {
                CreateOrderResponse response = orderService.createOrder(userId, request);
                orderApplied.countDown();
                awaitSignal(allowOrderCommit);
                return response;
            }));
            assertThat(orderApplied.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> deleteResult = executor.submit(() -> {
                deleteStarted.countDown();
                ticketProductService.deleteTicketProduct(productId);
            });
            assertThat(deleteStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(deleteResult);
            allowOrderCommit.countDown();
            CreateOrderResponse orderResponse = orderResult.get(20, TimeUnit.SECONDS);
            deleteResult.get(20, TimeUnit.SECONDS);

            // then: 작업이 모두 commit된 뒤 새로운 transaction에서 최종 DB 상태를 확인한다.
            assertThat(orderResponse.items()).singleElement().satisfies(item -> {
                assertThat(item.ticketProductId()).isEqualTo(productId);
                assertThat(item.quantity()).isEqualTo(2);
                assertThat(item.price()).isEqualTo(50000);
            });
            assertThat(orderResponse.totalAmount()).isEqualTo(100000);
            StockSnapshot snapshot = readSnapshot();
            assertSoftly(softly -> {
                softly.assertThat(snapshot.orderCount()).isEqualTo(1);
                softly.assertThat(snapshot.orderedQuantity()).isEqualTo(2);
                softly.assertThat(snapshot.status()).isEqualTo(TicketProductStatus.DELETED);
                softly.assertThat(snapshot.stock())
                        .as("주문 2개 차감 후 관리자 삭제가 재고를 덮어쓰지 않아야 한다")
                        .isEqualTo(8);
            });
        } finally {
            allowOrderCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    private CreateOrderRequest createOrderRequest() {
        return new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(productId, 2)),
                LocalDate.now(KstTime.ZONE).plusDays(1)
        );
    }

    private void awaitSignal(CountDownLatch signal) {
        try {
            if (!signal.await(40, TimeUnit.SECONDS)) {
                throw new IllegalStateException("transaction commit 재개 신호 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("transaction 대기 중 인터럽트 발생", exception);
        }
    }

    private void assertStillRunning(Future<?> result) {
        // 후행 요청이 500ms 안에 완료되지 않는지 확인한다. DB 내부의 lock wait 정보를 직접 조회하지는 않는다.
        assertThatThrownBy(() -> result.get(500, TimeUnit.MILLISECONDS))
                .as("선행 transaction이 commit되기 전에 후행 요청이 완료되면 안 된다")
                .isInstanceOf(TimeoutException.class);
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        // 중간 검증이 실패해도 DB 정리 전에 작업 스레드를 종료한다.
        executor.shutdown();
        if (!executor.awaitTermination(45, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("테스트에서 시작한 작업이 모두 종료되어야 한다")
                    .isTrue();
        }
    }

    private StockSnapshot readSnapshot() {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            TicketProduct product = entityManager.find(TicketProduct.class, productId);
            long orderCount = entityManager.createQuery(
                            "select count(o) from Order o where o.user.id = :userId", Long.class)
                    .setParameter("userId", userId).getSingleResult();
            long orderedQuantity = entityManager.createQuery(
                            "select coalesce(sum(i.quantity), 0) from OrderItem i where i.order.user.id = :userId", Long.class)
                    .setParameter("userId", userId).getSingleResult();
            return new StockSnapshot(product.getStock(), product.getStatus(), orderCount, orderedQuantity);
        }));
    }

    private record StockSnapshot(int stock, TicketProductStatus status, long orderCount, long orderedQuantity) {
    }
}
