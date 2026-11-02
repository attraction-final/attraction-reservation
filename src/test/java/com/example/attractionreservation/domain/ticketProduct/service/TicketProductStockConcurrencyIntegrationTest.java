package com.example.attractionreservation.domain.ticketProduct.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("최신 version으로 상품을 수정하면 응답에 flush 이후 version을 반환하고 DB 상태와 일치한다")
    void updateTicketProduct_CurrentVersion_ReturnsFlushedVersion() {
        // given
        StockSnapshot before = readSnapshot();
        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                "수정 상품", 60000, "수정 설명", TicketProductStatus.ON_SALE, 12, before.version()
        );

        // when
        UpdateTicketProductResponse response = ticketProductService.updateTicketProduct(productId, request);

        // then
        StockSnapshot after = readSnapshot();
        assertThat(response.version()).isEqualTo(before.version() + 1);
        assertThat(response.version()).isEqualTo(after.version());
        assertThat(response.stock()).isEqualTo(12);
        assertThat(after.stock()).isEqualTo(12);
        assertThat(after.name()).isEqualTo("수정 상품");
        assertThat(after.price()).isEqualTo(60000);
    }

    @Test
    @DisplayName("관리자 가격 수정이 먼저 잠그면 주문은 수정 commit 이후 최신 가격으로 구매하고 재고를 차감한다")
    void updateTicketProduct_UpdateLocksFirst_OrderUsesUpdatedPriceAndStock() throws Exception {
        // given
        StockSnapshot before = readSnapshot();
        UpdateTicketProductRequest updateRequest = new UpdateTicketProductRequest(
                before.name(), 60000, "가격 수정", TicketProductStatus.ON_SALE, 10, before.version()
        );
        CreateOrderRequest orderRequest = createOrderRequest();
        CountDownLatch updateApplied = new CountDownLatch(1);
        CountDownLatch allowUpdateCommit = new CountDownLatch(1);
        CountDownLatch orderStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<UpdateTicketProductResponse> updateResult = executor.submit(() -> transactionTemplate.execute(status -> {
                UpdateTicketProductResponse response = ticketProductService.updateTicketProduct(productId, updateRequest);
                updateApplied.countDown();
                awaitSignal(allowUpdateCommit);
                return response;
            }));
            assertThat(updateApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<CreateOrderResponse> orderResult = executor.submit(() -> {
                orderStarted.countDown();
                return orderService.createOrder(userId, orderRequest);
            });
            assertThat(orderStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(orderResult);
            allowUpdateCommit.countDown();
            UpdateTicketProductResponse updateResponse = updateResult.get(20, TimeUnit.SECONDS);
            CreateOrderResponse orderResponse = orderResult.get(20, TimeUnit.SECONDS);

            // then
            assertThat(updateResponse.version()).isEqualTo(before.version() + 1);
            assertThat(orderResponse.totalAmount()).isEqualTo(120000);
            assertThat(orderResponse.items()).singleElement().satisfies(item -> {
                assertThat(item.price()).isEqualTo(60000);
                assertThat(item.quantity()).isEqualTo(2);
            });
            StockSnapshot after = readSnapshot();
            assertThat(after.stock()).isEqualTo(8);
            assertThat(after.price()).isEqualTo(60000);
            assertThat(after.status()).isEqualTo(TicketProductStatus.ON_SALE);
            assertThat(after.version()).isEqualTo(before.version() + 2);
            assertThat(after.orderCount()).isEqualTo(1);
            assertThat(after.orderedQuantity()).isEqualTo(2);
        } finally {
            allowUpdateCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("주문이 먼저 잠그면 관리자 수정은 주문 commit 후 오래된 version으로 거절되고 재고 8개를 유지한다")
    void updateTicketProduct_OrderLocksFirst_RejectsStaleRequestWithoutRestoringStock() throws Exception {
        // given
        StockSnapshot before = readSnapshot();
        UpdateTicketProductRequest updateRequest = new UpdateTicketProductRequest(
                "오래된 화면 수정", 60000, "이전 재고를 포함한 요청", TicketProductStatus.ON_SALE, 10, before.version()
        );
        CreateOrderRequest orderRequest = createOrderRequest();
        CountDownLatch orderApplied = new CountDownLatch(1);
        CountDownLatch allowOrderCommit = new CountDownLatch(1);
        CountDownLatch updateStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<CreateOrderResponse> orderResult = executor.submit(() -> transactionTemplate.execute(status -> {
                CreateOrderResponse response = orderService.createOrder(userId, orderRequest);
                orderApplied.countDown();
                awaitSignal(allowOrderCommit);
                return response;
            }));
            assertThat(orderApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<UpdateTicketProductResponse> updateResult = executor.submit(() -> {
                updateStarted.countDown();
                return ticketProductService.updateTicketProduct(productId, updateRequest);
            });
            assertThat(updateStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(updateResult);
            allowOrderCommit.countDown();
            CreateOrderResponse orderResponse = orderResult.get(20, TimeUnit.SECONDS);

            // then
            assertThat(orderResponse.totalAmount()).isEqualTo(100000);
            assertThatThrownBy(() -> updateResult.get(20, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class, exception ->
                            assertThat(exception.getCause()).isInstanceOfSatisfying(
                                    BusinessException.class, businessException ->
                                            assertThat(businessException.getErrorCode())
                                                    .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT)));
            StockSnapshot after = readSnapshot();
            assertThat(after.stock()).isEqualTo(8);
            assertThat(after.name()).isEqualTo(before.name());
            assertThat(after.price()).isEqualTo(50000);
            assertThat(after.status()).isEqualTo(TicketProductStatus.ON_SALE);
            assertThat(after.version()).isEqualTo(before.version() + 1);
            assertThat(after.orderCount()).isEqualTo(1);
            assertThat(after.orderedQuantity()).isEqualTo(2);
        } finally {
            allowOrderCommit.countDown();
            shutdownExecutor(executor);
        }
    }

    @Test
    @DisplayName("공통 재고 복구가 version을 갱신하면 오래된 요청을 거절하고 최신 version으로 재요청 시 수정할 수 있다")
    void updateTicketProduct_StockRestored_RejectsStaleVersionAndAcceptsLatestVersion() {
        // given
        StockSnapshot before = readSnapshot();
        UpdateTicketProductRequest staleRequest = new UpdateTicketProductRequest(
                "오래된 화면 수정", 60000, "이전 재고를 포함한 요청", TicketProductStatus.ON_SALE, 10, before.version()
        );
        // 주문 취소·결제 실패·환불이 공통으로 사용하는 복구 Service만 검증한다. PG 환불 전체 흐름은 포함하지 않는다.
        ticketProductService.updateTicketProductsStockIncrease(Map.of(productId, 2));
        StockSnapshot restored = readSnapshot();

        // when & then
        assertThat(restored.stock()).isEqualTo(12);
        assertThat(restored.version()).isEqualTo(before.version() + 1);
        assertThatThrownBy(() -> ticketProductService.updateTicketProduct(productId, staleRequest))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT));
        assertThat(readSnapshot()).isEqualTo(restored);

        UpdateTicketProductRequest freshRequest = new UpdateTicketProductRequest(
                restored.name(), 55000, "다시 조회한 후 수정", TicketProductStatus.ON_SALE, 12, restored.version()
        );
        UpdateTicketProductResponse response = ticketProductService.updateTicketProduct(productId, freshRequest);
        StockSnapshot after = readSnapshot();
        assertThat(after.stock()).isEqualTo(12);
        assertThat(after.price()).isEqualTo(55000);
        assertThat(after.version()).isEqualTo(restored.version() + 1);
        assertThat(response.version()).isEqualTo(after.version());
    }

    @ParameterizedTest(name = "재고 복구 선행={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("관리자 재고 수정과 공통 재고 복구가 겹쳐도 선행 순서에 맞게 충돌을 거절하거나 복구 수량을 보존한다")
    void updateTicketProduct_ConcurrentStockRestore_PreservesStockOrRejectsStaleRequest(
            boolean restoreFirst
    ) throws Exception {
        // given
        StockSnapshot before = readSnapshot();
        UpdateTicketProductRequest request = new UpdateTicketProductRequest(
                before.name(), 60000, "관리자 재고 조정", TicketProductStatus.ON_SALE, 20, before.version()
        );
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // when
            Future<?> firstResult = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                if (restoreFirst) {
                    ticketProductService.updateTicketProductsStockIncrease(Map.of(productId, 2));
                } else {
                    ticketProductService.updateTicketProduct(productId, request);
                }
                firstApplied.countDown();
                awaitSignal(allowFirstCommit);
            }));
            assertThat(firstApplied.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> secondResult = executor.submit(() -> {
                secondStarted.countDown();
                if (restoreFirst) {
                    ticketProductService.updateTicketProduct(productId, request);
                } else {
                    ticketProductService.updateTicketProductsStockIncrease(Map.of(productId, 2));
                }
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertStillRunning(secondResult);
            allowFirstCommit.countDown();
            firstResult.get(20, TimeUnit.SECONDS);

            // then
            if (restoreFirst) {
                assertThatThrownBy(() -> secondResult.get(20, TimeUnit.SECONDS))
                        .isInstanceOfSatisfying(ExecutionException.class, exception ->
                                assertThat(exception.getCause()).isInstanceOfSatisfying(
                                        BusinessException.class, businessException ->
                                                assertThat(businessException.getErrorCode())
                                                        .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT)));
            } else {
                secondResult.get(20, TimeUnit.SECONDS);
            }
            StockSnapshot after = readSnapshot();
            assertThat(after.stock()).isEqualTo(restoreFirst ? 12 : 22);
            assertThat(after.price()).isEqualTo(restoreFirst ? 50000 : 60000);
            assertThat(after.version()).isEqualTo(before.version() + (restoreFirst ? 1 : 2));
            assertThat(after.status()).isEqualTo(TicketProductStatus.ON_SALE);
            assertThat(after.orderCount()).isZero();
            assertThat(after.orderedQuantity()).isZero();
        } finally {
            allowFirstCommit.countDown();
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
            return new StockSnapshot(product.getStock(), product.getStatus(), orderCount, orderedQuantity,
                    product.getVersion(), product.getPrice(), product.getName());
        }));
    }

    private record StockSnapshot(int stock, TicketProductStatus status, long orderCount, long orderedQuantity,
                                 Long version, int price, String name) {
    }
}
