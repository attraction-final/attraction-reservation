package com.example.attractionreservation.domain.payment.service;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.WebhookEvent;
import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.domain.payment.repository.WebhookEventRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 웹훅 Inbox 저장과 처리 상태 기록. 각 메서드는 짧은 트랜잭션으로 끝나고,
 * PG 호출은 {@code WebhookEventProcessor}가 트랜잭션 밖에서 수행한다.
 */
@Service
@RequiredArgsConstructor
public class WebhookEventCommandService {
    // 처리 중 상태로 이 시간이 지나면 서버가 멈춘 것으로 보고 다시 점유한다.
    static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final WebhookEventRepository webhookEventRepository;

    /**
     * 수신한 웹훅을 저장한다. 이미 받은 webhookId면 저장하지 않는다.
     *
     * <p>트랜잭션을 걸지 않는다. 바깥 트랜잭션 안에서 UNIQUE 위반이 나면 그 트랜잭션이
     * rollback-only로 표시되어 재조회와 커밋이 실패하므로, saveAndFlush가 자체 트랜잭션으로 실행되게 둔다.
     *
     * @return 새로 저장했으면 true, 이미 받은 웹훅이면 false
     */
    public boolean register(String webhookId, String eventType, String pgId, String payload) {
        if (webhookEventRepository.findByWebhookId(webhookId).isPresent()) {
            return false;
        }

        try {
            webhookEventRepository.saveAndFlush(WebhookEvent.receive(webhookId, pgId, eventType, payload));
            return true;
        } catch (DataIntegrityViolationException e) {
            // 같은 웹훅이 동시에 들어와 다른 요청이 먼저 저장한 경우
            if (webhookEventRepository.findByWebhookId(webhookId).isPresent()) {
                return false;
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<Long> findProcessableIds(Instant now, int limit) {
        return webhookEventRepository.findProcessableIds(
                WebhookStatus.RECEIVED, WebhookStatus.FAILED, WebhookStatus.PROCESSING,
                now, now.minus(STALE_PROCESSING_TIMEOUT), PageRequest.of(0, limit));
    }

    /**
     * 웹훅을 잠그고 처리할 수 있으면 PROCESSING으로 점유한다.
     * 다른 인스턴스가 먼저 점유했으면 상태를 다시 확인해 빈 값을 반환한다.
     *
     * @return 점유했으면 처리할 pgId
     */
    @Transactional
    public Optional<String> claim(Long eventId, Instant now) {
        WebhookEvent event = getWithLock(eventId);

        if (!event.isProcessable(now, STALE_PROCESSING_TIMEOUT)) {
            return Optional.empty();
        }

        event.markAsProcessing();
        return Optional.of(event.getPgId());
    }

    @Transactional
    public void recordResult(Long eventId, PaymentSyncResult result, Instant now) {
        WebhookEvent event = getWithLock(eventId);

        switch (result) {
            case NOT_OUR_PAYMENT -> event.markAsIgnored(now);
            case STATE_CONFLICT -> event.markAsDead("원장 상태와 충돌: 수동 확인 필요", now);
            case APPROVED, ALREADY_DONE, NOT_PAID, PG_FAILED, COMPENSATED -> event.markAsProcessed(now);
            // 새 결과가 추가됐는데 분기가 없으면 상태 변경 없이 넘어가지 않도록 실패시켜 재시도 흐름으로 보낸다.
            default -> throw new IllegalArgumentException("기록할 수 없는 처리 결과입니다: " + result);
        }
    }

    @Transactional
    public void recordFailure(Long eventId, String error, Instant now) {
        getWithLock(eventId).markAsFailed(error, now);
    }

    private WebhookEvent getWithLock(Long eventId) {
        return webhookEventRepository.findWithLockById(eventId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_WEBHOOK_NOT_FOUND));
    }
}
