package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.payment.entity.enums.WebhookStatus;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * PG 웹훅 수신 기록(Inbox). 결제 레코드보다 먼저 도착할 수 있으므로
 * Payment 와 연관관계를 맺지 않고 pg_id 문자열로만 조회한다.
 *
 * <p>created_at은 수신 시각, updated_at은 처리 중 멈춘 행을 다시 점유할 때의 기준이다.
 */
@Entity
@Table(name = "webhook_events", indexes = {
        @Index(name = "idx_webhook_events_pg_id", columnList = "pg_id"),
        @Index(name = "idx_webhook_events_status_retry", columnList = "status, next_retry_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent extends BaseTimeEntity {
    static final int MAX_ATTEMPTS = 5;
    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(1);
    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String webhookId; // 중복 수신 방지용

    @Column(length = 40)
    private String pgId; // 결제와 무관한 웹훅은 null

    @Column(nullable = false, length = 50)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private int attemptCount;

    private Instant nextRetryAt;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private Instant processedAt;

    private WebhookEvent(String webhookId, String pgId, String eventType, String payload) {
        this.webhookId = webhookId;
        this.pgId = pgId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = pgId == null ? WebhookStatus.IGNORED : WebhookStatus.RECEIVED;
    }

    // 결제와 무관한 웹훅(pgId 없음)은 처리 대상이 아니므로 IGNORED로 생성한다.
    public static WebhookEvent receive(String webhookId, String pgId, String eventType, String payload) {
        return new WebhookEvent(webhookId, pgId, eventType, payload);
    }

    /**
     * 지금 점유해 처리할 수 있는지 판단한다.
     * 처리 대기, 재시도 시각이 지난 실패, 처리 중에 멈춘 지 staleTimeout이 지난 웹훅이 대상이다.
     */
    public boolean isProcessable(Instant now, Duration staleTimeout) {
        return switch (status) {
            case RECEIVED -> true;
            case FAILED -> nextRetryAt != null && !nextRetryAt.isAfter(now);
            case PROCESSING -> getUpdatedAt() != null && !getUpdatedAt().isAfter(now.minus(staleTimeout));
            case PROCESSED, IGNORED, DEAD -> false;
        };
    }

    public void markAsProcessing() {
        transitTo(WebhookStatus.PROCESSING);
        this.attemptCount++;
    }

    public void markAsProcessed(Instant now) {
        transitTo(WebhookStatus.PROCESSED);
        this.processedAt = now;
        this.nextRetryAt = null;
    }

    // 처리 중 우리 결제가 아님을 확인한 경우
    public void markAsIgnored(Instant now) {
        transitTo(WebhookStatus.IGNORED);
        this.processedAt = now;
        this.nextRetryAt = null;
    }

    // 재시도해도 해결되지 않아 수동 처리가 필요한 경우
    public void markAsDead(String reason, Instant now) {
        transitTo(WebhookStatus.DEAD);
        this.nextRetryAt = null;
        this.processedAt = now;
        this.lastError = truncate(reason);
    }

    // 지수 백오프: 1분, 2분, 4분, 8분 후 재시도하고 MAX_ATTEMPTS번 실패하면 DEAD
    public void markAsFailed(String error, Instant now) {
        if (attemptCount >= MAX_ATTEMPTS) {
            transitTo(WebhookStatus.DEAD);
            this.processedAt = now;
            this.nextRetryAt = null;
        } else {
            transitTo(WebhookStatus.FAILED);
            this.nextRetryAt = now.plus(BASE_RETRY_DELAY.multipliedBy(1L << Math.max(0, attemptCount - 1)));
        }
        this.lastError = truncate(error);
    }

    private void transitTo(WebhookStatus nextStatus) {
        if (!this.status.canTransitTo(nextStatus)) {
            throw new BusinessException(ErrorCode.PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION);
        }

        this.status = nextStatus;
    }

    private String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }

        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
