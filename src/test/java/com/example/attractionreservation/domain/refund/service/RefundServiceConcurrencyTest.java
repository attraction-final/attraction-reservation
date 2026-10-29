package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class RefundServiceConcurrencyTest {

    private static final int THREAD_COUNT = 5;

    @Autowired
    private RefundService refundService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private String pgId;
    private Long orderItemId;
    private List<Long> ticketIds;

    // 10,000원 × 3장 주문의 결제 완료 상태. 티켓 3장이 발급되어 있다.
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
            // 실행 중 KST 자정을 넘어도 환불 기한(입장일 전날)에 걸리지 않도록 이틀 뒤로 잡는다.
            LocalDate visitDate = LocalDate.now(KstTime.ZONE).plusDays(2);
            Order order = Order.create(user, 30000, visitDate);
            OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
            ReflectionTestUtils.setField(orderItem, "order", order);
            ReflectionTestUtils.setField(orderItem, "ticketProduct", ticketProduct);
            ReflectionTestUtils.setField(orderItem, "price", 10000);
            ReflectionTestUtils.setField(orderItem, "quantity", 3);
            order.getOrderItems().add(orderItem);
            entityManager.persist(order);

            Payment payment = Payment.create(order);
            payment.markAsPaid(Instant.now());
            entityManager.persist(payment);

            List<Ticket> tickets = new ArrayList<>();
            for (int count = 0; count < 3; count++) {
                Ticket ticket = new Ticket(visitDate, orderItem);
                entityManager.persist(ticket);
                tickets.add(ticket);
            }

            userId = user.getId();
            pgId = payment.getPgId();
            orderItemId = orderItem.getId();
            ticketIds = tickets.stream().map(Ticket::getId).toList();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from RefundItem").executeUpdate();
            entityManager.createQuery("delete from Refund").executeUpdate();
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from Ticket").executeUpdate();
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from TicketProduct").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @DisplayName("같은 주문 상품에 부분 환불 요청이 동시에 들어와도 주문 수량을 넘겨 환불되지 않는다.")
    void createRefund_concurrentRequests_NoOverRefund() throws Exception {
        //given
        // 티켓 3장 중 서로 다른 2장씩 요청한다. 어떤 두 요청도 티켓이 겹치므로 한 건만 성공해야 한다.
        AtomicInteger requestIndex = new AtomicInteger();

        //when
        ConcurrentResult result = runConcurrently(() -> {
            int index = requestIndex.getAndIncrement();
            List<Long> requestTicketIds = List.of(ticketIds.get(index % 3), ticketIds.get((index + 1) % 3));
            return refundService.createRefund(userId, pgId, requestTicketIds, "단순 변심", Instant.now());
        });

        //then
        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(THREAD_COUNT - 1);
        assertThat(findRefundedQuantity()).isEqualTo(2L);
        assertThat(countRefundedTickets()).isEqualTo(2L);
    }

    @Test
    @DisplayName("전체 환불 요청이 동시에 들어와도 한 건만 저장되고 나머지는 환불할 수량이 없어 거절된다.")
    void createFullRefund_concurrentRequests_OnlyOneSaved() throws Exception {
        //when
        ConcurrentResult result = runConcurrently(() ->
                refundService.createFullRefund(userId, pgId, "단순 변심", Instant.now()));

        //then
        assertThat(result.unexpectedErrors()).isEmpty();
        assertThat(result.successCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(THREAD_COUNT - 1);
        assertThat(findRefundedQuantity()).isEqualTo(3L);
        assertThat(countRefundedTickets()).isEqualTo(3L);
    }

    // 같은 요청을 동시에 보내고, 성공·환불 수량 오류(REFUND_INVALID_QUANTITY)·그 밖의 오류를 센다.
    private ConcurrentResult runConcurrently(Callable<Long> request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    request.call();
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.REFUND_INVALID_QUANTITY) {
                        rejectedCount.incrementAndGet();
                    } else {
                        synchronized (unexpectedErrors) {
                            unexpectedErrors.add(e);
                        }
                    }
                } catch (Throwable e) {
                    synchronized (unexpectedErrors) {
                        unexpectedErrors.add(e);
                    }
                }
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

        return new ConcurrentResult(successCount.get(), rejectedCount.get(), unexpectedErrors);
    }

    private Long findRefundedQuantity() {
        return transactionTemplate.execute(status -> entityManager
                .createQuery("select coalesce(sum(ri.quantity), 0) from RefundItem ri where ri.orderItem.id = :orderItemId",
                        Long.class)
                .setParameter("orderItemId", orderItemId)
                .getSingleResult());
    }

    private Long countRefundedTickets() {
        return transactionTemplate.execute(status -> entityManager
                .createQuery("select count(t) from Ticket t where t.orderItem.id = :orderItemId and t.status = :status",
                        Long.class)
                .setParameter("orderItemId", orderItemId)
                .setParameter("status", TicketStatus.REFUNDED)
                .getSingleResult());
    }

    private record ConcurrentResult(int successCount, int rejectedCount, List<Throwable> unexpectedErrors) {
    }
}
