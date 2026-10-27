package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentCompensationService.class, QuerydslConfig.class})
class PaymentCompensationServiceTest {

    @Autowired
    private PaymentCompensationService paymentCompensationService;

    @Autowired
    private PaymentCompensationRepository paymentCompensationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("보상 요청을 점유하면 PROCESSING이 되고 PG 취소에 필요한 값을 반환한다.")
    void claim_requested_Claimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);

        //when
        Optional<ClaimedCompensation> claimed = paymentCompensationService.claim(id, now);

        //then
        assertThat(claimed).contains(new ClaimedCompensation(id, "pg-1", 9999L, "결제 금액 불일치", 1, null, null));
        PaymentCompensation found = find(id);
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 시각이 now보다 미래로 저장된 REQUESTED도 처리 대상으로 조회되고 바로 점유된다.")
    void claim_requestedWithFutureRequestTime_Claimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now.plusMillis(500));

        //when
        List<Long> ids = paymentCompensationService.findProcessableIds(now, 10);
        Optional<ClaimedCompensation> claimed = paymentCompensationService.claim(id, now);

        //then
        assertThat(ids).containsExactly(id);
        assertThat(claimed).isPresent();
    }

    @Test
    @DisplayName("이미 점유된 보상 요청은 다시 점유하지 않는다.")
    void claim_processing_NotClaimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        flushAndClear();

        //when
        Optional<ClaimedCompensation> claimed = paymentCompensationService.claim(id, now);

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(id).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리 중으로 멈춘 시각이 기준 시간보다 이전이면 처리 대상으로 조회되고 다시 점유된다.")
    void claim_staleProcessing_Claimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        claimAndStallAt(id, now.minus(PaymentCompensationService.STALE_PROCESSING_TIMEOUT).minusMillis(1));

        //when
        List<Long> ids = paymentCompensationService.findProcessableIds(now, 10);
        Optional<ClaimedCompensation> claimed = paymentCompensationService.claim(id, now);

        //then
        assertThat(ids).containsExactly(id);
        assertThat(claimed).isPresent();
        assertThat(find(id).getStatus()).isEqualTo(CompensationStatus.PROCESSING);
    }

    @Test
    @DisplayName("처리 중으로 멈춘 시각이 기준 시간과 정확히 같으면 처리 대상으로 조회된다.")
    void findProcessableIds_staleProcessingAtBoundary_Included() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        claimAndStallAt(id, now.minus(PaymentCompensationService.STALE_PROCESSING_TIMEOUT));

        //when
        List<Long> ids = paymentCompensationService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("처리 중으로 멈춘 보상 요청을 다시 점유하면 시도 횟수가 증가한다.")
    void claim_staleProcessing_IncreaseAttemptCount() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        claimAndStallAt(id, now.minus(PaymentCompensationService.STALE_PROCESSING_TIMEOUT).minusMillis(1));

        //when
        paymentCompensationService.claim(id, now);

        //then
        assertThat(find(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("완료된 보상 요청은 점유하지 않는다.")
    void claim_done_NotClaimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        paymentCompensationService.recordDone(id, 1, now);
        flushAndClear();

        //when
        Optional<ClaimedCompensation> claimed = paymentCompensationService.claim(id, now.plus(Duration.ofDays(1)));

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(id).getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(find(id).getProcessedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("실패한 보상 요청은 재시도 시각 전에는 점유하지 않고, 지난 뒤에는 점유한다.")
    void claim_failed_ClaimedOnlyAfterRetryTime() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        CompensationStatus status = paymentCompensationService.recordFailure(id, 1, "결제 취소에 실패했습니다.", now);
        flushAndClear();

        //when
        Optional<ClaimedCompensation> beforeRetry = paymentCompensationService.claim(id, now.plusSeconds(30));
        Optional<ClaimedCompensation> afterRetry = paymentCompensationService.claim(id, now.plus(Duration.ofMinutes(1)));

        //then
        assertThat(status).isEqualTo(CompensationStatus.FAILED);
        assertThat(beforeRetry).isEmpty();
        assertThat(afterRetry).isPresent();
        assertThat(find(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("최대 시도 횟수만큼 실패하면 recordFailure가 DEAD를 반환한다.")
    void recordFailure_maxAttempts_Dead() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        CompensationStatus status = null;

        //when
        for (int i = 0; i < 5; i++) {
            Instant attemptAt = now.plus(Duration.ofHours(i));
            paymentCompensationService.claim(id, attemptAt);
            status = paymentCompensationService.recordFailure(id, i + 1, "error", attemptAt);
        }

        //then
        assertThat(status).isEqualTo(CompensationStatus.DEAD);
        assertThat(find(id).getStatus()).isEqualTo(CompensationStatus.DEAD);
    }

    @Test
    @DisplayName("처리 대상으로 재시도 시각이 지난 요청·실패만 조회한다.")
    void findProcessableIds_mixedStatuses_OnlyProcessable() {
        //given
        Instant now = now();
        Long requested = save("pg-1", now);
        Long processing = save("pg-2", now);
        Long done = save("pg-3", now);
        Long retryDue = save("pg-4", now.minus(Duration.ofMinutes(10)));
        Long retryLater = save("pg-5", now);
        paymentCompensationService.claim(processing, now);
        paymentCompensationService.claim(done, now);
        paymentCompensationService.recordDone(done, 1, now);
        paymentCompensationService.claim(retryDue, now.minus(Duration.ofMinutes(10)));
        paymentCompensationService.recordFailure(retryDue, 1, "error", now.minus(Duration.ofMinutes(10)));
        paymentCompensationService.claim(retryLater, now);
        paymentCompensationService.recordFailure(retryLater, 1, "error", now);
        flushAndClear();

        //when
        List<Long> ids = paymentCompensationService.findProcessableIds(now, 10);

        //then
        assertThat(ids).containsExactly(requested, retryDue);
    }

    @Test
    @DisplayName("pgId로 보상 요청 ID를 찾고, 없으면 빈 값을 반환한다.")
    void findIdByPgId_existsOrNot() {
        //given
        Long id = save("pg-1", now());

        //when & then
        assertThat(paymentCompensationService.findIdByPgId("pg-1")).contains(id);
        assertThat(paymentCompensationService.findIdByPgId("unknown")).isEmpty();
    }

    @Test
    @DisplayName("PG 확인 대기로 기록하면 취소 처리 대상에서 빠지고, 1분 뒤부터 확인 대상으로 조회된다.")
    void recordPgPending_claimed_CheckableAfterOneMinute() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);

        //when
        paymentCompensationService.recordPgPending(id, 1, "cancel-1", now);
        flushAndClear();

        //then
        PaymentCompensation found = find(id);
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.PG_PENDING);
        assertThat(found.getPgCancellationId()).isEqualTo("cancel-1");
        assertThat(paymentCompensationService.findProcessableIds(now.plus(Duration.ofDays(1)), 10)).isEmpty();
        assertThat(paymentCompensationService.findPgCheckableIds(now.plusSeconds(30), 10)).isEmpty();
        assertThat(paymentCompensationService.findPgCheckableIds(now.plus(Duration.ofMinutes(1)), 10)).containsExactly(id);
    }

    @Test
    @DisplayName("확인할 차례인 PG 확인 대기 요청은 취소 내역 ID와 함께 꺼내고, 차례가 아니면 꺼내지 않는다.")
    void findPgCheckable_pgPending_ReturnWithCancellationId() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        paymentCompensationService.recordPgPending(id, 1, "cancel-1", now);
        flushAndClear();

        //when
        Optional<ClaimedCompensation> beforeCheck = paymentCompensationService.findPgCheckable(id, now.plusSeconds(30));
        Optional<ClaimedCompensation> afterCheck = paymentCompensationService.findPgCheckable(id, now.plus(Duration.ofMinutes(1)));

        //then
        assertThat(beforeCheck).isEmpty();
        assertThat(afterCheck).contains(
                new ClaimedCompensation(id, "pg-1", 9999L, "결제 금액 불일치", 1, "cancel-1", find(id).getPgPendingSince()));
    }

    @Test
    @DisplayName("PG 확인 대기 중 취소 완료를 기록하면 DONE이 되고 더 이상 확인 대상이 아니다.")
    void recordPgCancelled_pgPending_Done() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        paymentCompensationService.recordPgPending(id, 1, "cancel-1", now);

        //when
        paymentCompensationService.recordPgCancelled(id, find(id).getPgPendingSince(), now.plus(Duration.ofMinutes(1)));
        flushAndClear();

        //then
        assertThat(find(id).getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(paymentCompensationService.findPgCheckableIds(now.plus(Duration.ofDays(1)), 10)).isEmpty();
    }

    @Test
    @DisplayName("확인한 뒤 취소 실패와 재취소로 새 PG 확인 대기 주기가 됐으면 이전 주기의 늦은 확인 결과는 기록되지 않는다.")
    void reschedulePgCheck_previousCycle_Rejected() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        paymentCompensationService.recordPgPending(id, 1, "cancel-1", now);
        Instant firstCycle = find(id).getPgPendingSince();
        paymentCompensationService.recordPgCancelFailed(id, firstCycle, "PG 취소 실패 확인", now.plus(Duration.ofMinutes(1)));
        Instant retriedAt = now.plus(Duration.ofMinutes(3));
        paymentCompensationService.claim(id, retriedAt);
        paymentCompensationService.recordPgPending(id, 2, "cancel-2", retriedAt);
        flushAndClear();

        //when & then
        assertThatThrownBy(() -> paymentCompensationService.reschedulePgCheck(id, firstCycle, now.plus(Duration.ofMinutes(5))))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION.getMessage());
        PaymentCompensation found = find(id);
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.PG_PENDING);
        assertThat(found.getPgCancellationId()).isEqualTo("cancel-2");
        assertThat(found.getNextRetryAt()).isEqualTo(retriedAt.plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("멈춘 처리로 보고 다른 처리가 다시 점유했으면 이전 처리의 늦은 실패 결과는 기록되지 않는다.")
    void recordFailure_reclaimedByOther_Rejected() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        claimAndStallAt(id, now.minus(PaymentCompensationService.STALE_PROCESSING_TIMEOUT).minusMillis(1));
        paymentCompensationService.claim(id, now); // 다시 점유해 시도 횟수 2

        //when & then
        assertThatThrownBy(() -> paymentCompensationService.recordFailure(id, 1, "늦은 실패", now))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION.getMessage());
        PaymentCompensation found = find(id);
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.PROCESSING);
        assertThat(found.getAttemptCount()).isEqualTo(2);
        assertThat(found.getLastError()).isNull();
    }

    // DB가 Instant를 마이크로초 단위로 반올림해 저장하므로, 경계 비교가 흔들리지 않게 밀리초로 자른다.
    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private Long save(String pgId, Instant requestedAt) {
        return paymentCompensationRepository.saveAndFlush(
                PaymentCompensation.request(pgId, 9999L, "결제 금액 불일치", requestedAt)).getId();
    }

    // 점유한 뒤 처리 중 상태로 멈춘 것처럼 updated_at을 지정한다. 벌크 UPDATE는 Auditing을 거치지 않아 값이 유지된다.
    private void claimAndStallAt(Long id, Instant updatedAt) {
        paymentCompensationService.claim(id, updatedAt);
        flushAndClear();
        entityManager.createQuery("update PaymentCompensation c set c.updatedAt = :updatedAt where c.id = :id")
                .setParameter("updatedAt", updatedAt)
                .setParameter("id", id)
                .executeUpdate();
        flushAndClear();
    }

    private PaymentCompensation find(Long id) {
        flushAndClear();
        return paymentCompensationRepository.findById(id).orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
