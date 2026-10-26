package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.WebhookEvent;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.domain.payment.repository.WebhookEventRepository;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
@Import({JpaConfig.class, WebhookEventCommandService.class, QuerydslConfig.class})
class WebhookEventCommandServiceTest {

    private static final String PG_ID = "pg-id";

    @Autowired
    private WebhookEventCommandService webhookEventCommandService;

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("처음 받은 웹훅은 저장하고 true를 반환한다.")
    void register_newWebhook_Saved() {
        //when
        boolean registered = webhookEventCommandService.register("webhook-1", "WebhookTransactionPaid", PG_ID, "{}");

        //then
        assertThat(registered).isTrue();
        assertThat(webhookEventRepository.findByWebhookId("webhook-1")).isPresent();
    }

    @Test
    @DisplayName("이미 받은 웹훅은 다시 저장하지 않고 false를 반환한다.")
    void register_duplicateWebhook_Skipped() {
        //given
        webhookEventCommandService.register("webhook-1", "WebhookTransactionPaid", PG_ID, "{}");

        //when
        boolean registered = webhookEventCommandService.register("webhook-1", "WebhookTransactionPaid", PG_ID, "{}");

        //then
        assertThat(registered).isFalse();
        assertThat(webhookEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리 대기 웹훅을 점유하면 PROCESSING이 되고 pgId를 반환한다.")
    void claim_received_Claimed() {
        //given
        Long id = save("webhook-1", PG_ID);

        //when
        Optional<String> claimed = webhookEventCommandService.claim(id, Instant.now());

        //then
        assertThat(claimed).contains(PG_ID);
        assertThat(find(id).getStatus()).isEqualTo(WebhookStatus.PROCESSING);
        assertThat(find(id).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 점유된 웹훅은 다시 점유하지 않는다.")
    void claim_processing_NotClaimed() {
        //given
        Long id = save("webhook-1", PG_ID);
        webhookEventCommandService.claim(id, Instant.now());
        flushAndClear();

        //when
        Optional<String> claimed = webhookEventCommandService.claim(id, Instant.now());

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(id).getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("결제와 무관해 IGNORED로 저장된 웹훅은 점유하지 않는다.")
    void claim_ignored_NotClaimed() {
        //given
        Long id = save("webhook-1", null);

        //when
        Optional<String> claimed = webhookEventCommandService.claim(id, Instant.now());

        //then
        assertThat(claimed).isEmpty();
        assertThat(find(id).getStatus()).isEqualTo(WebhookStatus.IGNORED);
    }

    @Test
    @DisplayName("실패한 웹훅은 재시도 시각 전에는 점유하지 않고, 지난 뒤에는 점유한다.")
    void claim_failed_ClaimedOnlyAfterRetryTime() {
        //given
        Long id = save("webhook-1", PG_ID);
        Instant now = now();
        webhookEventCommandService.claim(id, now);
        webhookEventCommandService.recordFailure(id, "PG 조회 실패", now);
        flushAndClear();

        //when
        Optional<String> beforeRetry = webhookEventCommandService.claim(id, now.plusSeconds(30));
        Optional<String> afterRetry = webhookEventCommandService.claim(id, now.plus(Duration.ofMinutes(1)));

        //then
        assertThat(beforeRetry).isEmpty();
        assertThat(afterRetry).contains(PG_ID);
        assertThat(find(id).getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("처리 중에 멈춘 웹훅은 마지막 변경 후 5분이 지나기 전에는 점유하지 않고, 5분이 되면 다시 점유한다.")
    void claim_staleProcessing_ClaimedOnlyAfterStaleTimeout() {
        //given
        Long id = save("webhook-1", PG_ID);
        webhookEventCommandService.claim(id, now());
        Instant staleAt = find(id).getUpdatedAt().plus(Duration.ofMinutes(5));

        //when
        Optional<String> beforeStale = webhookEventCommandService.claim(id, staleAt.minusMillis(1));
        Optional<String> atStale = webhookEventCommandService.claim(id, staleAt);

        //then
        assertThat(beforeStale).isEmpty();
        assertThat(atStale).contains(PG_ID);
        assertThat(find(id).getStatus()).isEqualTo(WebhookStatus.PROCESSING);
        assertThat(find(id).getAttemptCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSyncResult.class, names = "STATE_CONFLICT")
    @DisplayName("수동 처리가 필요한 결과는 재시도 없이 DEAD로 기록한다.")
    void recordResult_manualRequired_Dead(PaymentSyncResult result) {
        //given
        Long id = save("webhook-1", PG_ID);
        Instant now = now();
        webhookEventCommandService.claim(id, now);

        //when
        webhookEventCommandService.recordResult(id, result, now);

        //then
        WebhookEvent event = find(id);
        assertThat(event.getStatus()).isEqualTo(WebhookStatus.DEAD);
        assertThat(event.getLastError()).isNotBlank();
        assertThat(event.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("우리 결제가 아니면 IGNORED, 그 밖의 결과는 PROCESSED로 기록한다.")
    void recordResult_byResult_Recorded() {
        //given
        Long notOurs = save("webhook-1", PG_ID);
        Long approved = save("webhook-2", PG_ID);
        Instant now = now();
        webhookEventCommandService.claim(notOurs, now);
        webhookEventCommandService.claim(approved, now);

        //when
        webhookEventCommandService.recordResult(notOurs, PaymentSyncResult.NOT_OUR_PAYMENT, now);
        webhookEventCommandService.recordResult(approved, PaymentSyncResult.APPROVED, now);

        //then
        assertThat(find(notOurs).getStatus()).isEqualTo(WebhookStatus.IGNORED);
        assertThat(find(approved).getStatus()).isEqualTo(WebhookStatus.PROCESSED);
    }

    @Test
    @DisplayName("처리 대상으로 처리 대기와 재시도 시각이 지난 실패 웹훅만 조회한다.")
    void findProcessableIds_mixedStatuses_OnlyProcessable() {
        //given
        Instant now = now();
        Long received = save("webhook-1", PG_ID);
        Long ignored = save("webhook-2", null);
        Long processing = save("webhook-3", PG_ID);
        Long retryDue = save("webhook-4", PG_ID);
        Long retryLater = save("webhook-5", PG_ID);
        webhookEventCommandService.claim(processing, now);
        webhookEventCommandService.claim(retryDue, now.minus(Duration.ofMinutes(10)));
        webhookEventCommandService.recordFailure(retryDue, "error", now.minus(Duration.ofMinutes(10)));
        webhookEventCommandService.claim(retryLater, now);
        webhookEventCommandService.recordFailure(retryLater, "error", now);
        flushAndClear();

        //when
        List<Long> ids = webhookEventCommandService.findProcessableIds(now, 10);

        //then
        assertThat(ids)
                .containsExactly(received, retryDue)
                .doesNotContain(ignored, processing, retryLater);
    }

    @Test
    @DisplayName("처리 중에 멈춘 웹훅은 마지막 변경 후 5분이 지나야 처리 대상으로 조회된다.")
    void findProcessableIds_staleProcessing_IncludedOnlyAfterStaleTimeout() {
        //given
        Long id = save("webhook-1", PG_ID);
        webhookEventCommandService.claim(id, now());
        Instant staleAt = find(id).getUpdatedAt().plus(Duration.ofMinutes(5));

        //when
        List<Long> beforeStale = webhookEventCommandService.findProcessableIds(staleAt.minusMillis(1), 10);
        List<Long> atStale = webhookEventCommandService.findProcessableIds(staleAt, 10);

        //then
        assertThat(beforeStale).isEmpty();
        assertThat(atStale).containsExactly(id);
    }

    // DB가 Instant를 마이크로초 단위로 반올림해 저장하므로, 경계 비교가 흔들리지 않게 밀리초로 자른다.
    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private Long save(String webhookId, String pgId) {
        return webhookEventRepository.saveAndFlush(
                WebhookEvent.receive(webhookId, pgId, "WebhookTransactionPaid", "{}")).getId();
    }

    private WebhookEvent find(Long id) {
        flushAndClear();
        return webhookEventRepository.findById(id).orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
