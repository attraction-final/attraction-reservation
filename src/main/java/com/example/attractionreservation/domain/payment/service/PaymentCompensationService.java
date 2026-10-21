package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.ClaimedCompensation;
import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;
import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentCompensationRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * PG 보상 취소 요청(Outbox) 저장과 처리 상태 기록. 각 메서드는 짧은 트랜잭션으로 끝나고,
 * PG 취소는 {@code PaymentCompensationProcessor}가 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
public class PaymentCompensationService {
    // 처리 중 상태로 이 시간이 지나면 서버가 멈춘 것으로 보고 다시 점유한다.
    // PG 취소 최대 소요 시간(통신 실패 3회 × 연결 5초 + 읽기 60초)보다 길어야 한다.
    static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final PaymentCompensationRepository paymentCompensationRepository;

    /**
     * 보상 요청을 저장한다. 결제 실패 처리 트랜잭션 안에서 호출되어 함께 커밋·롤백된다.
     */
    @Transactional
    public void saveCompensatingEvent(String pgId, Long amount, String reason) {
        PaymentCompensation paymentCompensation = PaymentCompensation.request(pgId, amount, reason, Instant.now());

        paymentCompensationRepository.save(paymentCompensation);
    }

    @Transactional(readOnly = true)
    public Optional<Long> findIdByPgId(String pgId) {
        return paymentCompensationRepository.findByPgId(pgId).map(PaymentCompensation::getId);
    }

    @Transactional(readOnly = true)
    public List<Long> findProcessableIds(Instant now, int limit) {
        return paymentCompensationRepository.findProcessableIds(
                CompensationStatus.REQUESTED, CompensationStatus.FAILED, CompensationStatus.PROCESSING,
                now, now.minus(STALE_PROCESSING_TIMEOUT), PageRequest.of(0, limit));
    }

    /**
     * 보상 요청을 잠그고 처리할 수 있으면 PROCESSING으로 점유한다.
     * 다른 처리(즉시 시도·스케줄러·다른 인스턴스)가 먼저 점유했으면 상태를 다시 확인해 빈 값을 반환한다.
     *
     * @return 점유했으면 PG 취소에 필요한 값
     */
    @Transactional
    public Optional<ClaimedCompensation> claim(Long compensationId, Instant now) {
        PaymentCompensation compensation = getWithLock(compensationId);

        if (!compensation.isProcessable(now, STALE_PROCESSING_TIMEOUT)) {
            return Optional.empty();
        }

        compensation.markAsProcessing();
        return Optional.of(ClaimedCompensation.from(compensation));
    }

    @Transactional
    public void recordDone(Long compensationId, Instant now) {
        getWithLock(compensationId).markAsDone(now);
    }

    /**
     * 실패를 기록하고 백오프 후 재시도를 예약한다. 최대 시도 횟수를 넘기면 DEAD가 된다.
     *
     * @return 기록 후 상태 (FAILED 또는 DEAD)
     */
    @Transactional
    public CompensationStatus recordFailure(Long compensationId, String error, Instant now) {
        PaymentCompensation compensation = getWithLock(compensationId);
        compensation.markAsFailed(error, now);
        return compensation.getStatus();
    }

    private PaymentCompensation getWithLock(Long compensationId) {
        return paymentCompensationRepository.findWithLockById(compensationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_COMPENSATION_NOT_FOUND));
    }
}
