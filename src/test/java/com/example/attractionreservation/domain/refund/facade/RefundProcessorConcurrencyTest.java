package com.example.attractionreservation.domain.refund.facade;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.WebhookEvent;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.domain.payment.facade.WebhookEventProcessor;
import com.example.attractionreservation.domain.payment.port.PaymentGateway;
import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.service.RefundService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
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
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

/**
 * 즉시 시도와 스케줄러가 겹쳐도 PG 취소가 중복되지 않고, 같은 결제의 환불이 한 번에 하나씩 처리되는지
 * 실제 MySQL 락으로 검증한다. 취소 웹훅으로 환불 상태를 맞추는 흐름도 웹훅 처리부터 DB까지 함께 검증한다. PG는 응답에 시간이 걸리는 가짜 구현으로 대체한다.
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
    private WebhookEventProcessor webhookEventProcessor;

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
                return succeeded();
            } finally {
                inFlight.decrementAndGet();
            }
        }).given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from WebhookEvent").executeUpdate();
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
        refundProcessor.processPending(10, 10);

        //then
        assertThat(maxInFlight.get()).isEqualTo(1);
        assertThat(pgCalls).hasSize(2);
        long firstAmount = pgCalls.get(0).get(0);
        assertThat(pgCalls.get(0).get(1)).isEqualTo(50000L);
        assertThat(pgCalls.get(1).get(1)).isEqualTo(50000L - firstAmount);
        assertThat(findRefund(adultRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findRefund(childRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
    }

    @Test
    @DisplayName("PG 취소 응답을 기다리는 사이 취소 웹훅이 먼저 완료 처리해도 환불은 한 번만 완료되고 오류 없이 끝난다.")
    void syncWithPg_whileCancelInFlight_CompleteOnce() throws Exception {
        //given
        Long refundId = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(new PaymentGatewayResponse(
                pgId, GatewayPaymentStatus.PARTIAL_CANCELLED, 50000L, 10000L, Instant.now(), List.of())));

        // PG 취소가 PG에 도착한 뒤 응답 전에 멈춰, 그 사이 웹훅을 처리한다.
        CountDownLatch cancelArrived = new CountDownLatch(1);
        CountDownLatch webhookDone = new CountDownLatch(1);
        willAnswer(invocation -> {
            pgCalls.add(List.of(invocation.getArgument(2), invocation.getArgument(3)));
            cancelArrived.countDown();
            webhookDone.await(10, TimeUnit.SECONDS);
            return succeeded();
        }).given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());

        //when
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> processing = executor.submit(() -> refundProcessor.processById(refundId));
        PaymentSyncResult syncResult;
        try {
            assertThat(cancelArrived.await(10, TimeUnit.SECONDS)).isTrue();
            syncResult = refundProcessor.syncWithPg(pgId);
        } finally {
            webhookDone.countDown();
        }
        processing.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        //then
        assertThat(syncResult).isEqualTo(PaymentSyncResult.REFUND_SYNCED);
        assertThat(pgCalls).containsExactly(List.of(10000L, 50000L));
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(refundProcessor.syncWithPg(pgId)).isEqualTo(PaymentSyncResult.ALREADY_DONE);
    }

    @Test
    @DisplayName("PG에는 취소됐지만 실패로 기록된 환불은 취소 웹훅을 처리하면 COMPLETED가 되고 웹훅은 PROCESSED가 된다.")
    void processWebhook_cancelledButRecordedFailed_RefundCompleted() {
        //given
        // PG 응답이 타임아웃 나 실패로 기록됐지만 PG에는 취소가 반영된 상황
        Long refundId = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());
        willThrow(new BusinessException(ErrorCode.PAYMENT_CANCELLATION_FAILED))
                .given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());
        refundProcessor.processById(refundId);
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.FAILED);

        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(new PaymentGatewayResponse(
                pgId, GatewayPaymentStatus.PARTIAL_CANCELLED, 50000L, 10000L, Instant.now(), List.of())));
        Long eventId = transactionTemplate.execute(status -> {
            WebhookEvent event = WebhookEvent.receive("webhook-1", pgId, "WebhookTransactionCancelled", "{}");
            entityManager.persist(event);
            return event.getId();
        });

        //when
        webhookEventProcessor.process(eventId);

        //then
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        WebhookStatus webhookStatus = transactionTemplate.execute(
                status -> entityManager.find(WebhookEvent.class, eventId).getStatus());
        assertThat(webhookStatus).isEqualTo(WebhookStatus.PROCESSED);
    }

    @Test
    @DisplayName("앞선 환불이 PG 취소 확정 대기 중이면 같은 결제의 다음 환불은 동시에 처리해도 PG에 보내지 않고, 확정된 뒤 남은 취소 가능 금액으로 보낸다.")
    void process_previousRefundPgPending_NextRefundWaits() throws Exception {
        //given
        // 성인 1장(10,000원) 취소는 PG가 접수만 하고, 어린이 4장(20,000원) 취소는 바로 완료된다.
        willAnswer(invocation -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(PG_LATENCY_MILLIS);
                Long amount = invocation.getArgument(2);
                pgCalls.add(List.of(amount, invocation.getArgument(3)));
                return amount == 10000L
                        ? new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, amount)
                        : succeeded();
            } finally {
                inFlight.decrementAndGet();
            }
        }).given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());

        Long adultRefund = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());
        refundProcessor.processById(adultRefund);
        assertThat(findRefund(adultRefund).getStatus()).isEqualTo(RefundStatus.PG_PENDING);
        Long childRefund = refundService.createRefund(userId, pgId, Map.of(childItemId, 4), "단순 변심", Instant.now());

        //when
        // 확정 대기 중에 즉시 시도와 스케줄러가 다음 환불을 동시에 처리한다.
        AtomicInteger turn = new AtomicInteger();
        runConcurrently(() -> {
            if (turn.getAndIncrement() % 2 == 0) {
                refundProcessor.processById(childRefund);
            } else {
                refundProcessor.processPending(10, 10);
            }
        });

        //then
        assertThat(pgCalls).containsExactly(List.of(10000L, 50000L));
        assertThat(findRefund(childRefund).getStatus()).isEqualTo(RefundStatus.REQUESTED);

        //when
        // PG가 앞선 취소를 완료하고 확인 시각이 되면, 스케줄러가 앞선 환불을 확정한 뒤 다음 환불을 보낸다.
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(new PaymentGatewayResponse(
                pgId, GatewayPaymentStatus.PARTIAL_CANCELLED, 50000L, 10000L, Instant.now(),
                List.of(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED, 10000L)))));
        makePgCheckDue(adultRefund);
        refundProcessor.processPending(10, 10);
        refundProcessor.processPending(10, 10);

        //then
        assertThat(findRefund(adultRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(findRefund(childRefund).getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(pgCalls).containsExactly(List.of(10000L, 50000L), List.of(20000L, 40000L));
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    @DisplayName("확정 대기 환불을 취소 웹훅과 스케줄러가 동시에 확정해도 환불은 한 번만 완료되고 오류 없이 끝난다.")
    void confirm_webhookAndSchedulerConcurrently_CompleteOnce() throws Exception {
        //given
        Long refundId = createPgPendingRefund();
        // PG 조회에 시간이 걸리게 해 웹훅과 스케줄러의 확인이 겹치게 한다.
        willAnswer(invocation -> {
            Thread.sleep(PG_LATENCY_MILLIS);
            return Optional.of(new PaymentGatewayResponse(
                    pgId, GatewayPaymentStatus.PARTIAL_CANCELLED, 50000L, 10000L, Instant.now(),
                    List.of(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.SUCCEEDED, 10000L))));
        }).given(paymentGateway).getPayment(pgId);
        makePgCheckDue(refundId);
        Queue<PaymentSyncResult> syncResults = new ConcurrentLinkedQueue<>();

        //when
        AtomicInteger turn = new AtomicInteger();
        runConcurrently(() -> {
            if (turn.getAndIncrement() % 2 == 0) {
                syncResults.add(refundProcessor.syncWithPg(pgId));
            } else {
                refundProcessor.processPending(10, 10);
            }
        });

        //then
        Refund refund = findRefund(refundId);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(refund.getCompletedAt()).isNotNull();
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(syncResults).hasSize(THREAD_COUNT / 2)
                .allMatch(result -> result == PaymentSyncResult.REFUND_SYNCED || result == PaymentSyncResult.ALREADY_DONE);
        assertThat(syncResults).filteredOn(result -> result == PaymentSyncResult.REFUND_SYNCED).hasSizeLessThanOrEqualTo(1);
        assertThat(pgCalls).containsExactly(List.of(10000L, 50000L));
    }

    @Test
    @DisplayName("확정 대기 환불의 취소가 아직 접수 상태면 취소 웹훅은 PROCESSED가 되고 환불은 확인 대기로 남는다(금액으로 잘못 맞추지 않는다).")
    void processWebhook_pgPendingStillRequested_WebhookProcessed() {
        //given
        Long refundId = createPgPendingRefund();
        // 접수된 취소 금액이 누적 취소 금액에 포함돼 보이는 경우(리스크 A)에도 금액으로 완료하지 않아야 한다.
        given(paymentGateway.getPayment(pgId)).willReturn(Optional.of(new PaymentGatewayResponse(
                pgId, GatewayPaymentStatus.PAID, 50000L, 10000L, Instant.now(),
                List.of(new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, 10000L)))));
        Long eventId = transactionTemplate.execute(status -> {
            WebhookEvent event = WebhookEvent.receive("webhook-1", pgId, "WebhookTransactionCancelPending", "{}");
            entityManager.persist(event);
            return event.getId();
        });

        //when
        webhookEventProcessor.process(eventId);

        //then
        WebhookStatus webhookStatus = transactionTemplate.execute(
                status -> entityManager.find(WebhookEvent.class, eventId).getStatus());
        assertThat(webhookStatus).isEqualTo(WebhookStatus.PROCESSED);
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.PG_PENDING);
        assertThat(findPaymentStatus()).isEqualTo(PaymentStatus.PAID);
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

    // 성인 1장(10,000원) 환불을 PG가 접수만 한 상태(PG_PENDING, 취소 내역 cancel-1)로 만든다.
    private Long createPgPendingRefund() {
        willAnswer(invocation -> {
            pgCalls.add(List.of(invocation.getArgument(2), invocation.getArgument(3)));
            return new PaymentCancellationResponse("cancel-1", GatewayCancellationStatus.REQUESTED, 10000L);
        }).given(paymentGateway).cancelPayment(anyString(), anyString(), anyLong(), anyLong());

        Long refundId = refundService.createRefund(userId, pgId, Map.of(adultItemId, 1), "단순 변심", Instant.now());
        refundProcessor.processById(refundId);
        assertThat(findRefund(refundId).getStatus()).isEqualTo(RefundStatus.PG_PENDING);
        return refundId;
    }

    private static PaymentCancellationResponse succeeded() {
        return new PaymentCancellationResponse("cancel-ok", GatewayCancellationStatus.SUCCEEDED);
    }

    // 확인 대기 환불의 다음 확인 시각을 지금 이전으로 당긴다.
    private void makePgCheckDue(Long refundId) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createQuery("update Refund r set r.nextRetryAt = :due where r.id = :id")
                .setParameter("due", Instant.now().minusSeconds(1))
                .setParameter("id", refundId)
                .executeUpdate());
    }

    private Refund findRefund(Long refundId) {
        return transactionTemplate.execute(status -> entityManager.find(Refund.class, refundId));
    }

    private PaymentStatus findPaymentStatus() {
        return transactionTemplate.execute(status -> entityManager.find(Payment.class, paymentId).getStatus());
    }
}
