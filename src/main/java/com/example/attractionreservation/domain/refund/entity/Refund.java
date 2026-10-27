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
import java.util.Objects;

/**
 * 환불. 환불 요청 자체를 PG 취소 Outbox로 사용한다.
 * 요청이 커밋되면 즉시 시도나 스케줄러 재시도로 PG 취소가 시도된다.
 *
 * <p>updated_at은 처리 중 멈춘 환불을 다시 점유할 때의 기준이다.
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund extends BaseTimeEntity {
    static final int MAX_ATTEMPTS = 5;
    private static final Duration BASE_RETRY_DELAY = Duration.ofMinutes(1);
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
            case COMPLETED, DEAD -> false;
        };
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
