package com.example.attractionreservation.domain.payment.service;

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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
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
    @DisplayName("결제 승인 트랜잭션이 결제를 읽은 뒤 취소가 먼저 커밋되면, 승인은 낙관적 락 충돌로 실패한다.")
    void cancelPayment_paidAfterCancelCommitted_OptimisticLockFail() throws Exception {
        //given
        ExecutorService executor = Executors.newSingleThreadExecutor();

        //when & then
        try {
            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                // 승인 트랜잭션이 PENDING 상태의 결제를 먼저 읽는다.
                Payment payment = entityManager.find(Payment.class, paymentId);
                assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

                // 그 사이 다른 스레드에서 결제 취소가 커밋된다.
                runAndWait(executor, () -> paymentService.cancelPayment(userId, orderId));

                // 이미 읽어 둔 PENDING 상태 기준으로 승인을 시도한다.
                payment.markAsPaid();
            })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        } finally {
            executor.shutdownNow();
        }

        Payment result = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
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
                } catch (ObjectOptimisticLockingFailureException e) {
                    failCount.incrementAndGet();
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
        assertThat(result.getVersion()).isEqualTo(1);
    }

    private void runAndWait(ExecutorService executor, Runnable task) {
        try {
            executor.submit(task).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
