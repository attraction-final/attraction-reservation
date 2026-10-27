package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 즉시 시도와 스케줄러가 겹쳐도 PG 취소가 중복되지 않고, 같은 결제의 환불이 한 번에 하나씩 처리되는지
 * 실제 MySQL 락으로 검증한다. PG는 응답에 시간이 걸리는 가짜 구현으로 대체한다.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class RefundProcessorConcurrencyTest {

    private static final int THREAD_COUNT = 6;
    private static final long PG_LATENCY_MILLIS = 300;

    @Autowired
    private RefundProcessor refundProcessor;

    @Autowired
    private RefundService refundService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private final List<List<Long>> pgCalls = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    private Long userId;
    private String pgId;
    private Long paymentId;
    private Long adultItemId;
    private Long childItemId;

    // 성인 10,000원 × 3장, 어린이 5,000원 × 4장 = 50,000원 결제
    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("test@email.com")
                    .password("password")
                    .nickname("name")
                    .build();
            entityManager.persist(user);

            TicketProduct ticketProduct = new TicketProduct(
                    "종일이용권", 10000, "하루 동안 이용 가능한 입장권", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE);
            entityManager.persist(ticketProduct);

            // 주문 생성 기능이 없어 주문 상품을 직접 만든다. 주문을 저장하면 cascade로 함께 저장된다.
            Order order = Order.create(user, 50000);
            OrderItem adult = addOrderItem(order, ticketProduct, 10000, 3);
            OrderItem child = addOrderItem(order, ticketProduct, 5000, 4);
            order.markAsPaid();
            entityManager.persist(order);

            Payment payment = Payment.create(order);
            payment.markAsPaid(Instant.now());
            entityManager.persist(payment);

            userId = user.getId();
            pgId = payment.getPgId();
            paymentId = payment.getId();
            adultItemId = adult.getId();
            childItemId = child.getId();
        });

        // PG 취소: 동시에 몇 건이 PG에 가 있는지 기록하고, 응답에 시간이 걸리게 한다.
        willAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(PG_LATENCY_MILLIS);
                pgCalls.add(List.of(invocation.getArgument(2), invocation.getArgument(3)));
                return null;
            } finally {
                inFlight.decrementAndGet();
            }
        }).given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from RefundItem").executeUpdate();
            entityManager.createQuery("delete from Refund").executeUpdate();
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from TicketProduct").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @DisplayName("즉시 시도와 스케줄러가 같은 환불을 동시에 처리해도 PG 취소는 한 번만 보낸다.")
    void process_sameRefundConcurrently_CancelOnce() throws Exception {
        //given
        Long refundId = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());

        //when
        runConcurrently(() -> refundProcessor.processById(refundId));

        //then
        assertThat(pgCalls).containsExactly(List.of(10000L, 50000L));
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("같은 결제의 환불 두 건을 동시에 처리해도 PG에는 한 번에 하나씩 가고, PG 취소 가능 금액은 앞선 환불을 뺀 값이다.")
    void process_twoRefundsOfSamePayment_OneAtATime() throws Exception {
        //given
        Long adultRefund = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());
        Long childRefund = refundService.createRefund(userId, pgId, Map.of(childItemId, 4), "단순 변심", Instant.now());

        //when
        // 두 환불을 절반씩 동시에 처리한 뒤, 건너뛴 환불은 스케줄러처럼 이어서 처리한다.
        AtomicInteger turn = new AtomicInteger();
        runConcurrently(() -> refundProcessor.processById(turn.getAndIncrement() % 2 == 0 ? adultRefund : childRefund));
        refundProcessor.processPending(10);

        //then
        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(pgCalls).hasSize(2);
        long firstAmount = pgCalls.get(0).get(0);
        assertThat(pgCalls.get(0).get(1)).isEqualTo(50000L);
        assertThat(pgCalls.get(1).get(1)).isEqualTo(50000L - firstAmount);
        assertThat(findRefund(adultRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findRefund(childRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    private void runConcurrently(Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                task.run();
                return null;
            }));
        }
        startLatch.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private OrderItem addOrderItem(Order order, TicketProduct ticketProduct, int price, int quantity) {
        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "ticketProduct", ticketProduct);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        order.getOrderItems().add(orderItem);
        return orderItem;
    }

    private Refund findRefund(Long refundId) {
        return transactionTemplate.execute(status -> entityManager.find(Refund.class, refundId));
    }

    private PaymentStatus findPaymentStatus() {
        return transactionTemplate.execute(status -> entityManager.find(Payment.class, paymentId).getStatus());
    }
}
