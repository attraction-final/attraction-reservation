package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.payment.entity.enums.CompensationStatus;
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
 * PG 보상 취소 요청(Outbox). 결제 실패 처리와 같은 트랜잭션에서 저장해,
 * 커밋된 보상은 즉시 시도나 스케줄러 재시도로 반드시 PG 취소가 시도되게 한다.
 *
 * <p>결제당 보상은 한 건이므로 pg_id에 UNIQUE를 건다.
 * created_at은 요청 시각, updated_at은 처리 중 멈춘 행을 다시 점유할 때의 기준이다.
 */
@Entity
@Table(name = "payment_compensations",
        indexes = @Index(name = "idx_payment_compensations_status_retry", columnList = "status, next_retry_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCompensation extends BaseTimeEntity {
    static final int MAX_ATTEMPTS = 5;
    // PG가 취소를 접수한 뒤 이 시간 안에 확정되지 않으면 수동 확인으로 넘긴다.
    static final Duration PG_CHECK_TIMEOUT = Duration.ofHours(24);
    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration MIN_PG_CHECK_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_PG_CHECK_DELAY = Duration.ofMinutes(30);
    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String pgId;

    @Column(nullable = false)
    private Long amount; // PG가 실제 결제한 금액(취소할 금액). 원장 금액과 다를 수 있다.

    @Column(nullable = false, length = 100)
    private String reason; // PG 취소 API에 보내는 사유. 재시도 때도 같은 값을 보낸다.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompensationStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextRetryAt;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private Instant processedAt;

    @Column(length = 100)
    private String pgCancellationId; // PG 취소 내역 ID. PG_PENDING 확정에 쓴다. 응답을 해석하지 못했으면 null

    private Instant pgPendingSince; // PG_PENDING이 된 시각. 확인 간격과 기한의 기준

    private PaymentCompensation(String pgId, Long amount, String reason, Instant now) {
        this.pgId = pgId;
        this.amount = amount;
        this.reason = reason;
        this.status = CompensationStatus.REQUESTED;
        this.nextRetryAt = now; // 요청 시각. REQUESTED는 이 값과 무관하게 바로 처리 대상이다
    }

    public static PaymentCompensation request(String pgId, Long amount, String reason, Instant now) {
        if (amount == null || amount <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_AMOUNT);
        }

        return new PaymentCompensation(pgId, amount, reason, now);
    }

    /**
     * 지금 점유해 처리할 수 있는지 판단한다.
     * 아직 시도하지 않은 요청, 재시도 시각이 지난 실패, 처리 중에 멈춘 지 staleTimeout이 지난 요청이 대상이다.
     * REQUESTED는 next_retry_at과 비교하지 않는다. DB가 시각을 반올림해 저장하면 생성 직후 값이 now보다
     * 미래가 되어 즉시 시도가 건너뛰어질 수 있기 때문이다.
     */
    public boolean isProcessable(Instant now, Duration staleTimeout) {
        return switch (status) {
            case REQUESTED -> true;
            case FAILED -> !nextRetryAt.isAfter(now);
            case PROCESSING -> getUpdatedAt() != null && !getUpdatedAt().isAfter(now.minus(staleTimeout));
            // PG가 취소를 접수했을 수 있으므로 취소를 다시 보내지 않는다. 확정은 isPgCheckable 대상에서 조회로 한다.
            case PG_PENDING, DONE, DEAD -> false;
        };
    }

    // PG 취소 결과를 조회로 확인할 차례인지 판단한다.
    public boolean isPgCheckable(Instant now) {
        return status == CompensationStatus.PG_PENDING && !nextRetryAt.isAfter(now);
    }

    /**
     * 결과를 기록하려는 처리가 지금 이 요청을 점유한 처리인지 확인한다.
     * 처리 중 멈춘 행으로 보고 다른 처리가 다시 점유했다면 시도 횟수가 달라지므로, 이전 처리의 늦은 결과는 거부한다.
     *
     * @param claimedAttempt 점유할 때의 시도 횟수
     */
    public void verifyClaimedBy(int claimedAttempt) {
        if (status != CompensationStatus.PROCESSING || attemptCount != claimedAttempt) {
            throw new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION);
        }
    }

    public void markAsProcessing() {
        transitTo(CompensationStatus.PROCESSING);
        this.attemptCount++;
    }

    public void markAsDone(Instant now) {
        transitTo(CompensationStatus.DONE);
        this.processedAt = now;
    }

    // PG가 취소를 접수했지만 끝나지 않았거나 결과를 알 수 없다. 1분 뒤 첫 확인을 예약한다.
    public void markAsPgPending(String cancellationId, Instant now) {
        transitTo(CompensationStatus.PG_PENDING);
        this.pgCancellationId = cancellationId;
        this.pgPendingSince = now;
        this.nextRetryAt = now.plus(MIN_PG_CHECK_DELAY);
    }

    // 조회로 PG 취소 완료를 확인했다.
    public void confirmPgCancelled(Instant now) {
        requirePgPending();
        markAsDone(now);
    }

    // 조회로 PG 취소 실패를 확인했다. 취소를 다시 보낼 수 있으므로 일반 실패와 같이 재시도한다.
    public void confirmPgCancelFailed(String error, Instant now) {
        requirePgPending();
        markAsFailed(error, now);
    }

    /**
     * 아직 확정되지 않아 다음 확인을 예약한다. 간격은 PG_PENDING이 된 뒤 지난 시간(1분~30분)이라 대략 두 배씩 늘어난다.
     * 확인 기한이 지났으면 DEAD로 보낸다.
     */
    public void reschedulePgCheck(Instant now) {
        requirePgPending();

        if (!now.isBefore(pgPendingSince.plus(PG_CHECK_TIMEOUT))) {
            transitTo(CompensationStatus.DEAD);
            this.processedAt = now;
            this.lastError = "PG 취소 확인 기한 초과";
            return;
        }

        transitTo(CompensationStatus.PG_PENDING);
        this.nextRetryAt = now.plus(clamp(Duration.between(pgPendingSince, now)));
    }

    // 지수 백오프: 1분, 2분, 4분, 8분 후 재시도하고 MAX_ATTEMPTS번 실패하면 DEAD
    public void markAsFailed(String error, Instant now) {
        if (attemptCount >= MAX_ATTEMPTS) {
            transitTo(CompensationStatus.DEAD);
            this.processedAt = now;
        } else {
            transitTo(CompensationStatus.FAILED);
            this.nextRetryAt = now.plus(BASE_RETRY_DELAY.multipliedBy(1L << (attemptCount - 1)));
        }
        this.lastError = truncate(error);
    }

    private void requirePgPending() {
        if (status != CompensationStatus.PG_PENDING) {
            throw new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION);
        }
    }

    private Duration clamp(Duration delay) {
        if (delay.compareTo(MIN_PG_CHECK_DELAY) < 0) {
            return MIN_PG_CHECK_DELAY;
        }

        return delay.compareTo(MAX_PG_CHECK_DELAY) > 0 ? MAX_PG_CHECK_DELAY : delay;
    }

    private void transitTo(CompensationStatus nextStatus) {
        if (!this.status.canTransitTo(nextStatus)) {
            throw new BusinessException(ErrorCode.PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION);
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
