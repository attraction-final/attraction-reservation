package com.example.attractionreservation.domain.refund.entity;

import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 환불. 환불 요청 자체를 PG 취소 Outbox로 사용한다.
 * 요청이 커밋되면 즉시 시도나 스케줄러 재시도로 PG 취소가 시도된다.
 *
 * <p>updated_at은 처리 중 멈춘 환불을 다시 점유할 때의 기준이다.
 */
@Entity
// 스케줄러의 처리 대상·확인 대상 조회(상태 + 다음 재시도·확인 시각)에 쓴다.
@Table(name = "refunds",
        indexes = @Index(name = "idx_refunds_status_retry", columnList = "status, next_retry_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTimeEntity {
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(nullable = false)
    private int refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RefundStatus status;

    @Column(nullable = false, length = 200)
    private String reason;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant completedAt;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextRetryAt;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(length = 100)
    private String pgCancellationId; // PG 취소 내역 ID. PG_PENDING 확정에 쓴다. 응답을 해석하지 못했으면 null

    private Instant pgPendingSince; // PG_PENDING이 된 시각. 확인 간격과 기한의 기준

    @OneToMany(mappedBy = "refund", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RefundItem> refundItems = new ArrayList<>();

    private Refund(Payment payment, String reason, Instant requestedAt) {
        this.payment = payment;
        this.reason = reason;
        this.requestedAt = requestedAt;
        this.refundAmount = 0;
        this.status = RefundStatus.REQUESTED;
        this.nextRetryAt = requestedAt; // REQUESTED는 이 값과 무관하게 바로 처리 대상이다
    }

    public static Refund create(Payment payment, String reason, Instant requestedAt) {
        return new Refund(payment, reason, requestedAt);
    }

    public void addRefundItem(RefundItem refundItem) {
        // 지연 로딩 프록시끼리도 비교할 수 있도록 엔티티가 아닌 ID로 비교한다.
        if (!Objects.equals(this.payment.getOrder().getId(), refundItem.getOrderItem().getOrder().getId())) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_ORDER_ITEM);
        }

        refundItems.add(refundItem);
        refundItem.assignRefund(this);
        this.refundAmount += refundItem.getRefundPrice();
    }

    /**
     * 지금 점유해 PG 취소를 시도할 수 있는지 판단한다.
     * 아직 시도하지 않은 환불, 재시도 시각이 지난 실패, 처리 중에 멈춘 지 staleTimeout이 지난 환불이 대상이다.
     * REQUESTED는 next_retry_at과 비교하지 않는다. DB가 시각을 반올림해 저장하면 생성 직후 값이 now보다
     * 미래가 되어 즉시 시도가 건너뛰어질 수 있기 때문이다.
     */
    public boolean isProcessable(Instant now, Duration staleTimeout) {
        return switch (status) {
            case REQUESTED -> true;
            case FAILED -> !nextRetryAt.isAfter(now);
            case PROCESSING -> getUpdatedAt() != null && !getUpdatedAt().isAfter(now.minus(staleTimeout));
            // PG가 취소를 접수했을 수 있으므로 취소를 다시 보내지 않는다. 확정은 isPgCheckable 대상에서 조회로 한다.
            case PG_PENDING, COMPLETED, DEAD -> false;
        };
    }

    // PG 취소 결과를 조회로 확인할 차례인지 판단한다.
    public boolean isPgCheckable(Instant now) {
        return status == RefundStatus.PG_PENDING && !nextRetryAt.isAfter(now);
    }

    /**
     * 결과를 기록하려는 처리가 지금 이 환불을 점유한 처리인지 확인한다.
     * 처리 중 멈춘 환불로 보고 다른 처리가 다시 점유했다면 시도 횟수가 달라지므로, 이전 처리의 늦은 결과는 거부한다.
     *
     * @param claimedAttempt 점유할 때의 시도 횟수
     */
    public void verifyClaimedBy(int claimedAttempt) {
        if (status != RefundStatus.PROCESSING || attemptCount != claimedAttempt) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION);
        }
    }

    /**
     * PG 확인 결과를 기록하려는 처리가 지금의 PG_PENDING 주기를 확인한 것인지 확인한다.
     * 확인 사이에 취소 실패 → 재취소로 새 PG_PENDING이 되면 시작 시각이 달라지므로, 이전 주기의 늦은 결과는 거부한다.
     *
     * @param checkedPendingSince 확인 대상으로 꺼낼 때의 PG_PENDING 시작 시각
     */
    public void verifyPgPendingSince(Instant checkedPendingSince) {
        if (status != RefundStatus.PG_PENDING || !pgPendingSince.equals(checkedPendingSince)) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION);
        }
    }

    public void markAsProcessing() {
        transitTo(RefundStatus.PROCESSING);
        this.attemptCount++;
    }

    // PG 취소 성공 응답을 받은 처리만 완료한다. 점유한 환불(PROCESSING)만 대상이다.
    public void markAsComplete(Instant completedAt) {
        if (this.status != RefundStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION);
        }

        transitTo(RefundStatus.COMPLETED);
        this.completedAt = completedAt;
    }

    // PG 재조회로 취소가 반영된 것을 확인한 환불을 완료한다. 실패·DEAD로 기록됐어도 PG 기준으로 맞춘다.
    public void markAsCompleteByPg(Instant completedAt) {
        transitTo(RefundStatus.COMPLETED);
        this.completedAt = completedAt;
    }

    // PG가 취소를 접수했지만 끝나지 않았거나 결과를 알 수 없다. 1분 뒤 첫 확인을 예약한다.
    public void markAsPgPending(String cancellationId, Instant now) {
        transitTo(RefundStatus.PG_PENDING);
        this.pgCancellationId = cancellationId;
        this.pgPendingSince = now;
        this.nextRetryAt = now.plus(MIN_PG_CHECK_DELAY);
    }

    // 조회로 PG 취소 완료를 확인했다.
    public void confirmPgCancelled(Instant completedAt) {
        requirePgPending();
        markAsCompleteByPg(completedAt);
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
            transitTo(RefundStatus.DEAD);
            this.lastError = "PG 취소 확인 기한 초과";
            return;
        }

        transitTo(RefundStatus.PG_PENDING);
        this.nextRetryAt = now.plus(clamp(Duration.between(pgPendingSince, now)));
    }

    // 지수 백오프: 1분, 2분, 4분, 8분 후 재시도하고 MAX_ATTEMPTS번 실패하면 DEAD
    public void markAsFailed(String error, Instant now) {
        if (attemptCount >= MAX_ATTEMPTS) {
            transitTo(RefundStatus.DEAD);
        } else {
            transitTo(RefundStatus.FAILED);
            this.nextRetryAt = now.plus(BASE_RETRY_DELAY.multipliedBy(1L << (attemptCount - 1)));
        }
        this.lastError = truncate(error);
    }

    // 재시도해도 결과가 같거나 PG에 이미 반영됐을 수 있는 실패. 시도 횟수와 관계없이 바로 DEAD
    public void markAsDead(String error) {
        transitTo(RefundStatus.DEAD);
        this.lastError = truncate(error);
    }

    public Map<Long, Integer> getQuantityByTicketProductId() {
        return refundItems.stream()
                .collect(Collectors.toMap(
                        refundItem -> refundItem.getOrderItem().getTicketProduct().getId(),
                        RefundItem::getQuantity,
                        Integer::sum
                ));
    }

    private void requirePgPending() {
        if (status != RefundStatus.PG_PENDING) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION);
        }
    }

    private Duration clamp(Duration delay) {
        if (delay.compareTo(MIN_PG_CHECK_DELAY) < 0) {
            return MIN_PG_CHECK_DELAY;
        }

        return delay.compareTo(MAX_PG_CHECK_DELAY) > 0 ? MAX_PG_CHECK_DELAY : delay;
    }

    private void transitTo(RefundStatus nextStatus) {
        if (!this.status.canTransitTo(nextStatus)) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_STATUS_TRANSITION);
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
