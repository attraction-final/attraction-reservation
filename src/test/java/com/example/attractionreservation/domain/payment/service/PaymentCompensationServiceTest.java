package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import com.example.attractionreservation.global.config.JpaConfig;
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

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, PaymentCompensationService.class})
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
        assertThat(claimed).contains(new ClaimedCompensation(id, "pg-1", 9999L, "결제 금액 불일치"));
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
    @DisplayName("완료된 보상 요청은 점유하지 않는다.")
    void claim_done_NotClaimed() {
        //given
        Instant now = now();
        Long id = save("pg-1", now);
        paymentCompensationService.claim(id, now);
        paymentCompensationService.recordDone(id, now);
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
        CompensationStatus status = paymentCompensationService.recordFailure(id, "결제 취소에 실패했습니다.", now);
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
            status = paymentCompensationService.recordFailure(id, "error", attemptAt);
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
        paymentCompensationService.recordDone(done, now);
        paymentCompensationService.claim(retryDue, now.minus(Duration.ofMinutes(10)));
        paymentCompensationService.recordFailure(retryDue, "error", now.minus(Duration.ofMinutes(10)));
        paymentCompensationService.claim(retryLater, now);
        paymentCompensationService.recordFailure(retryLater, "error", now);
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

    // DB가 Instant를 마이크로초 단위로 반올림해 저장하므로, 경계 비교가 흔들리지 않게 밀리초로 자른다.
    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private Long save(String pgId, Instant requestedAt) {
        return paymentCompensationRepository.saveAndFlush(
                PaymentCompensation.request(pgId, 9999L, "결제 금액 불일치", requestedAt)).getId();
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
