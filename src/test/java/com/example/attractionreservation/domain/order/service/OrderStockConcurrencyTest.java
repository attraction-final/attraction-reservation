package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.payment.service.PaymentCommandService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 주문 생성·취소의 재고 차감/복구는 실제 DB 락으로 보호되므로 Testcontainers MySQL에서 검증한다.
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class OrderStockConcurrencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderCancelProcessor orderCancelProcessor;

    @Autowired
    private PaymentCommandService paymentCommandService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TicketProductRepository ticketProductRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long userId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("stock-concurrency@test.com")
                    .password("password")
                    .nickname("재고동시성")
                    .build();
            entityManager.persist(user);
            userId = user.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from TicketProduct").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @DisplayName("재고보다 많은 동시 주문이 들어와도 재고만큼만 성공하고 재고는 음수가 되지 않는다")
    void createOrder_concurrentRequestsExceedStock_neverOversells() throws Exception {
        // given
        int stock = 5;
        int requestCount = 15;
        Long productId = createProduct(stock);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int count = 0; count < requestCount; count++) {
            tasks.add(() -> orderService.createOrder(userId, createRequest(productId, 1)));
        }

        // when
        List<Throwable> failures = runConcurrently(tasks);

        // then: 재고 수만큼만 성공하고 나머지는 재고 부족으로 거절된다.
        long successCount = failures.stream().filter(failure -> failure == null).count();
        assertThat(successCount).isEqualTo(stock);
        assertThat(failures.stream().filter(failure -> failure != null))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.TICKET_PRODUCT_STOCK_INSUFFICIENT)));

        TicketProduct product = ticketProductRepository.findById(productId).orElseThrow();
        assertThat(product.getStock()).isZero();
        assertThat(product.getStatus()).isEqualTo(TicketProductStatus.OUT_OF_STOCK);
        assertThat(orderRepository.count()).isEqualTo(stock);
        assertThat(paymentRepository.count()).isEqualTo(stock);
    }

    @Test
    @DisplayName("상품 순서를 반대로 담은 주문이 동시에 들어와도 데드락 없이 모두 성공한다")
    void createOrder_oppositeProductOrder_noDeadlock() throws Exception {
        // given
        Long firstProductId = createProduct(1000);
        Long secondProductId = createProduct(1000);

        CreateOrderRequest forward = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(firstProductId, 1),
                new CreateOrderRequest.Item(secondProductId, 1)
        ));
        CreateOrderRequest reverse = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(secondProductId, 1),
                new CreateOrderRequest.Item(firstProductId, 1)
        ));

        List<Callable<?>> tasks = new ArrayList<>();
        for (int count = 0; count < 10; count++) {
            tasks.add(() -> orderService.createOrder(userId, forward));
            tasks.add(() -> orderService.createOrder(userId, reverse));
        }

        // when
        List<Throwable> failures = runConcurrently(tasks);

        // then
        assertThat(failures).allMatch(failure -> failure == null);
        assertThat(ticketProductRepository.findById(firstProductId).orElseThrow().getStock())
                .isEqualTo(980);
        assertThat(ticketProductRepository.findById(secondProductId).orElseThrow().getStock())
                .isEqualTo(980);
    }

    @Test
    @DisplayName("매진된 상품의 주문을 취소하면 재고가 돌아오고 다시 판매 중이 된다")
    void updateOrderByCancel_soldOutProduct_restoresStockAndReopensSale() {
        // given
        Long productId = createProduct(3);
        Long orderId = orderService.createOrder(userId, createRequest(productId, 3)).orderId();

        TicketProduct soldOut = ticketProductRepository.findById(productId).orElseThrow();
        assertThat(soldOut.getStock()).isZero();
        assertThat(soldOut.getStatus()).isEqualTo(TicketProductStatus.OUT_OF_STOCK);

        // when
        orderService.updateOrderByCancel(userId, orderId);

        // then
        TicketProduct restored = ticketProductRepository.findById(productId).orElseThrow();
        assertThat(restored.getStock()).isEqualTo(3);
        assertThat(restored.getStatus()).isEqualTo(TicketProductStatus.ON_SALE);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("사용자 취소와 만료 취소가 같은 주문에 동시에 실행돼도 재고는 한 번만 복구된다")
    void cancel_userAndExpiredCancelConcurrently_restoresStockOnce() throws Exception {
        // given
        int initialStock = 10;
        Long productId = createProduct(initialStock);
        Long orderId = orderService.createOrder(userId, createRequest(productId, 2)).orderId();
        assertThat(ticketProductRepository.findById(productId).orElseThrow().getStock())
                .isEqualTo(initialStock - 2);

        List<Callable<?>> tasks = new ArrayList<>();
        for (int count = 0; count < 5; count++) {
            tasks.add(() -> orderService.updateOrderByCancel(userId, orderId));
            tasks.add(() -> orderCancelProcessor.cancelExpiredOrder(orderId, userId, Instant.now()));
        }

        // when
        List<Throwable> failures = runConcurrently(tasks);

        // then: 먼저 처리한 하나만 복구하고, 나머지는 취소할 수 없는 주문으로 거절되거나 건너뛴다.
        assertThat(failures.stream().filter(failure -> failure != null))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)));

        assertThat(ticketProductRepository.findById(productId).orElseThrow().getStock())
                .isEqualTo(initialStock);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findAll())
                .extracting(Payment::getStatus)
                .containsExactly(PaymentStatus.CANCELLED);
    }

    @Test
    @DisplayName("사용자 주문 취소와 PG 결제 실패 처리가 동시에 실행돼도 재고는 한 번만 복구된다")
    void cancel_userCancelAndPaymentFailureConcurrently_restoresStockOnce() throws Exception {
        // given
        int initialStock = 10;
        Long productId = createProduct(initialStock);
        Long orderId = orderService.createOrder(userId, createRequest(productId, 2)).orderId();
        String pgId = paymentRepository.findAll().get(0).getPgId();

        List<Callable<?>> tasks = new ArrayList<>();
        for (int count = 0; count < 5; count++) {
            tasks.add(() -> orderService.updateOrderByCancel(userId, orderId));
            tasks.add(() -> paymentCommandService.failPayment(pgId));
        }

        // when
        List<Throwable> failures = runConcurrently(tasks);

        // then: 먼저 처리한 쪽만 복구하고, 나머지는 취소할 수 없는 주문으로 거절되거나 건너뛴다.
        assertThat(failures.stream().filter(failure -> failure != null))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)));

        assertThat(ticketProductRepository.findById(productId).orElseThrow().getStock())
                .isEqualTo(initialStock);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("결제 승인과 사용자 주문 취소가 경쟁하면 취소가 이겼을 때만 재고가 복구된다")
    void cancel_approveAndUserCancelConcurrently_restoresStockOnlyWhenCancelled() throws Exception {
        // given
        int initialStock = 10;
        Long productId = createProduct(initialStock);
        Long orderId = orderService.createOrder(userId, createRequest(productId, 2)).orderId();
        String pgId = paymentRepository.findAll().get(0).getPgId();
        long amount = orderRepository.findById(orderId).orElseThrow().getTotalAmount();

        List<Callable<?>> tasks = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            tasks.add(() -> orderService.updateOrderByCancel(userId, orderId));
            tasks.add(() -> paymentCommandService.approvePayment(pgId, Instant.now(), amount));
        }

        // when
        List<Throwable> failures = runConcurrently(tasks);

        // then: 결제 승인은 예외 없이 끝나고, 취소는 이긴 한 번을 빼면 취소할 수 없는 주문으로 거절된다.
        assertThat(failures.stream().filter(failure -> failure != null))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOfSatisfying(BusinessException.class, exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)));

        OrderStatus orderStatus = orderRepository.findById(orderId).orElseThrow().getStatus();
        int stock = ticketProductRepository.findById(productId).orElseThrow().getStock();
        if (orderStatus == OrderStatus.CANCELLED) {
            assertThat(stock).isEqualTo(initialStock);
        } else {
            // 승인이 이기면 주문이 완료되고 재고는 차감된 채로 남는다.
            assertThat(orderStatus).isEqualTo(OrderStatus.COMPLETED);
            assertThat(stock).isEqualTo(initialStock - 2);
        }
    }

    private Long createProduct(int stock) {
        return transactionTemplate.execute(status -> {
            TicketProduct product = new TicketProduct(
                    "동시성상품", 50000, "재고 동시성 테스트 상품",
                    TicketType.ALL_DAY, stock, TicketProductStatus.ON_SALE
            );
            entityManager.persist(product);
            return product.getId();
        });
    }

    private CreateOrderRequest createRequest(Long productId, int quantity) {
        return new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(productId, quantity)
        ));
    }

    // 모든 작업을 같은 시점에 시작시키고, 작업별 실패(성공이면 null)를 모아 반환한다.
    private List<Throwable> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();

        try {
            for (Callable<?> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        task.call();
                        return null;
                    } catch (Throwable throwable) {
                        return throwable;
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                failures.add(future.get(60, TimeUnit.SECONDS));
            }
            return failures;
        } finally {
            executor.shutdownNow();
        }
    }
}
