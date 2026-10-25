package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class PaymentServiceConcurrencyTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long userId;
    private Long orderId;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = User.builder()
                    .email("test@email.com")
                    .password("password")
                    .nickname("name")
                    .build();
            entityManager.persist(user);

            Order order = Order.create(user, 10000);
            entityManager.persist(order);

            Payment payment = Payment.create(order);
            entityManager.persist(payment);

            userId = user.getId();
            orderId = order.getId();
            paymentId = payment.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createQuery("delete from Payment").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
            entityManager.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    @DisplayName("결제 승인 트랜잭션이 락을 잡고 있으면 취소는 대기하고, 승인 커밋 후 결제 완료 상태를 읽어 취소가 거부된다.")
    void cancelPayment_waitForApproveLock_Fail() throws Exception {
        //given
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Future<?>> cancelFutures = new ArrayList<>();

        //when
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 승인 트랜잭션이 결제에 비관적 락을 먼저 잡는다.
                Payment payment = paymentRepository.findWithLockByOrderId(orderId).orElseThrow();
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

                // 그 사이 다른 스레드에서 취소를 요청하면 락 해제까지 대기한다.
                Future<?> cancelFuture = executor.submit(() -> paymentService.cancelPayment(userId, orderId));
                cancelFutures.add(cancelFuture);
                assertThatThrownBy(() -> cancelFuture.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(TimeoutException.class);

                payment.markAsPaid();
            });

            //then
            assertThatThrownBy(() -> cancelFutures.get(0).get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(ErrorCode.PAYMENT_CANCEL_PROHIBITED.getMessage());
        } finally {
            executor.shutdownNow();
        }

        Payment result = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("다른 트랜잭션이 락을 3초 넘게 잡고 있으면 취소는 락 획득 실패로 끝나고 결제 상태는 바뀌지 않는다.")
    void cancelPayment_lockWaitTimeout_Fail() {
        //given
        ExecutorService executor = Executors.newSingleThreadExecutor();

        //when & then
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 다른 트랜잭션이 결제에 비관적 락을 잡은 상태를 유지한다.
                paymentRepository.findWithLockByOrderId(orderId).orElseThrow();

                Future<?> cancelFuture = executor.submit(() -> paymentService.cancelPayment(userId, orderId));
                assertThatThrownBy(() -> cancelFuture.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOf(CannotAcquireLockException.class);
            });
        } finally {
            executor.shutdownNow();
        }

        Payment result = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("같은 결제에 취소 요청이 동시에 들어와도 한 건만 성공한다.")
    void cancelPayment_concurrentRequests_OnlyOneSuccess() throws Exception {
        //given
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        List<Throwable> unexpectedErrors = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        //when
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    paymentService.cancelPayment(userId, orderId);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.PAYMENT_CANCEL_PROHIBITED) {
                        failCount.incrementAndGet();
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
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        //then
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        Payment result = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }
}
