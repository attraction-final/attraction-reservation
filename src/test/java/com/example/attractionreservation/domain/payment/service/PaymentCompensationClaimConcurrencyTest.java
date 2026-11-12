package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.TestcontainersConfiguration;
import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보상 요청 점유(claim)의 비관적 락이 실제 MySQL에서 경쟁 호출을 직렬화하는지 검증한다.
 * 즉시 시도·스케줄러·다른 인스턴스가 같은 보상을 동시에 점유해 PG 취소를 두 번 보내지 않게 하는 장치다.
 *
 * <p>테스트 메서드 트랜잭션에 묶이지 않도록 @Transactional 없이 실행하고 데이터를 직접 정리한다.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Import(TestcontainersConfiguration.class)
class PaymentCompensationClaimConcurrencyTest {

    @Autowired
    private PaymentCompensationService paymentCompensationService;

    @Autowired
    private PaymentCompensationRepository paymentCompensationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager entityManager;

    private Long compensationId;

    @BeforeEach
    void setUp() {
        compensationId = paymentCompensationRepository.saveAndFlush(
                PaymentCompensation.request("pg-id", 9999L, "결제 금액 불일치", Instant.now())).getId();
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status ->
                entityManager.createQuery("delete from PaymentCompensation").executeUpdate());
    }

    @Test
    @DisplayName("같은 보상 요청을 두 처리가 동시에 점유해도 한 처리만 점유하고 시도 횟수는 1이다.")
    void claim_concurrent_OnlyOneClaimed() throws Exception {
        //given
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Optional<ClaimedCompensation>>> futures = new ArrayList<>();

        //when
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return paymentCompensationService.claim(compensationId, Instant.now());
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            //then
            List<Optional<ClaimedCompensation>> results = new ArrayList<>();
            for (Future<Optional<ClaimedCompensation>> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        PaymentCompensation result = paymentCompensationRepository.findById(compensationId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
        assertThat(result.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 처리가 점유 트랜잭션에서 락을 잡고 있으면 점유는 대기하고, 커밋 후 처리 중 상태를 읽어 점유하지 않는다.")
    void claim_waitForLockHolder_NotClaimedAfterCommit() throws Exception {
        //given
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Future<Optional<ClaimedCompensation>>> futures = new ArrayList<>();

        //when
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 먼저 점유하는 처리가 보상 요청에 비관적 락을 잡고 PROCESSING으로 바꾼다.
                PaymentCompensation compensation = paymentCompensationRepository.findWithLockById(compensationId).orElseThrow();
                compensation.markAsProcessing();

                // 그 사이 다른 처리의 점유는 락 해제까지 대기한다.
                Future<Optional<ClaimedCompensation>> future =
                        executor.submit(() -> paymentCompensationService.claim(compensationId, Instant.now()));
                futures.add(future);
                assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                        .isInstanceOf(TimeoutException.class);
            });

            //then
            assertThat(futures.get(0).get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            executor.shutdownNow();
        }

        PaymentCompensation result = paymentCompensationRepository.findById(compensationId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
        assertThat(result.getAttemptCount()).isEqualTo(1);
    }
}
