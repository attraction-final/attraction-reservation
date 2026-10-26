package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.WebhookEvent;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.global.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
class WebhookEventRepositoryTest {

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Test
    @DisplayName("결제 pgId(UUID 36자)를 가진 웹훅을 저장하고 webhookId로 조회할 수 있다.")
    void save_uuidPgId_Success() {
        //given
        String pgId = UUID.randomUUID().toString();

        //when
        webhookEventRepository.saveAndFlush(WebhookEvent.receive("webhook-1", pgId, "WebhookTransactionPaid", "{}"));

        //then
        WebhookEvent found = webhookEventRepository.findByWebhookId("webhook-1").orElseThrow();
        assertThat(found.getPgId()).isEqualTo(pgId);
        assertThat(found.getStatus()).isEqualTo(WebhookStatus.RECEIVED);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("pgId가 없는 웹훅도 저장할 수 있다.")
    void save_withoutPgId_Success() {
        //when
        webhookEventRepository.saveAndFlush(WebhookEvent.receive("webhook-1", null, "WebhookBillingKeyIssued", "{}"));

        //then
        WebhookEvent found = webhookEventRepository.findByWebhookId("webhook-1").orElseThrow();
        assertThat(found.getPgId()).isNull();
        assertThat(found.getStatus()).isEqualTo(WebhookStatus.IGNORED);
    }

    @Test
    @DisplayName("같은 webhookId로 다시 저장하면 UNIQUE 제약 위반이 발생한다.")
    void save_duplicateWebhookId_Fail() {
        //given
        webhookEventRepository.saveAndFlush(WebhookEvent.receive("webhook-1", "pg-id", "WebhookTransactionPaid", "{}"));
        WebhookEvent duplicate = WebhookEvent.receive("webhook-1", "pg-id", "WebhookTransactionPaid", "{}");

        //when & then
        assertThatThrownBy(() -> webhookEventRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
