package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.global.config.JpaConfig;
import jakarta.persistence.EntityManager;
import com.example.attractionreservation.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaConfig.class, QuerydslConfig.class})
class PaymentCompensationRepositoryTest {

    @Autowired
    private PaymentCompensationRepository paymentCompensationRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("결제 pgId(UUID 36자)와 PG 결제 금액으로 보상 요청을 저장하고 pgId로 조회할 수 있다.")
    void save_compensation_Success() {
        //given
        String pgId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        //when
        paymentCompensationRepository.saveAndFlush(
                PaymentCompensation.request(pgId, 3_000_000_000L, "결제 금액 불일치", now));
        entityManager.clear();

        //then
        PaymentCompensation found = paymentCompensationRepository.findByPgId(pgId).orElseThrow();
        assertThat(found.getAmount()).isEqualTo(3_000_000_000L);
        assertThat(found.getStatus()).isEqualTo(CompensationStatus.REQUESTED);
        assertThat(found.getNextRetryAt()).isEqualTo(now);
        assertThat(found.getProcessedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 결제에 보상 요청을 두 번 저장하면 UNIQUE 제약 위반이 발생한다.")
    void save_duplicatePgId_Fail() {
        //given
        Instant now = Instant.now();
        paymentCompensationRepository.saveAndFlush(PaymentCompensation.request("pg-id", 9999L, "결제 금액 불일치", now));

        //when & then
        assertThatThrownBy(() -> paymentCompensationRepository.saveAndFlush(
                PaymentCompensation.request("pg-id", 9999L, "결제 금액 불일치", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
