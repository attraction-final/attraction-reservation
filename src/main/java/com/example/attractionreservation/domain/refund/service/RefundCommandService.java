package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.refund.dto.ClaimedRefund;
import com.example.attractionreservation.domain.refund.dto.RefundedQuantity;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.repository.RefundItemRepository;
import com.example.attractionreservation.domain.refund.repository.RefundRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 환불 PG 취소 처리 단계의 상태 기록. 각 메서드는 짧은 트랜잭션으로 끝나고,
 * PG 취소는 {@code RefundProcessor}가 트랜잭션 밖에서 수행한다.
 *
 * <p>환불 요청 생성과 같이 결제 → 환불 순서로 잠근다. 같은 결제의 환불은 결제 락 때문에 순서대로 처리된다.
 *
 * <p>점유와 완료 기록은 READ COMMITTED로 실행한다. 락을 잡기 전 조회(결제 ID)에서 읽기 스냅샷이 만들어지므로,
 * REPEATABLE READ(MySQL 기본값)에서는 락을 기다린 뒤에도 그 전 스냅샷을 읽어 다른 처리가 커밋한
 * PROCESSING·COMPLETED를 보지 못한다.
 */
@Service
@RequiredArgsConstructor
public class RefundCommandService {
    // 처리 중 상태로 이 시간이 지나면 서버가 멈춘 것으로 보고 다시 점유한다.
    // PG 취소 최대 소요 시간(통신 실패 3회 × (연결 5초 + 읽기 10초) = 45초)보다 길어야 한다.
    static final Duration STALE_PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    // PG에 보낸 적이 있어 PG 취소 금액으로 완료 여부를 맞출 수 있는 환불 상태
    private static final List<RefundStatus> PG_SYNC_TARGET_STATUSES =
            List.of(RefundStatus.PROCESSING, RefundStatus.FAILED, RefundStatus.DEAD);

    private final RefundRepository refundRepository;
    private final RefundItemRepository refundItemRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public List<Long> findProcessableIds(Instant now, int limit) {
        return refundRepository.findProcessableIds(
                RefundStatus.REQUESTED, RefundStatus.FAILED, RefundStatus.PROCESSING,
                now, now.minus(STALE_PROCESSING_TIMEOUT), PageRequest.of(0, limit));
    }

    /**
     * 결제와 환불을 잠그고 처리할 수 있으면 PROCESSING으로 점유한다.
     * 다른 처리(즉시 시도·스케줄러·다른 인스턴스)가 먼저 점유했으면 빈 값을 반환한다.
     *
     * <p>같은 결제의 다른 환불이 PG 취소 중(PROCESSING)이면 점유하지 않는다. 결제 락은 점유 트랜잭션 동안만
     * 유지되므로, 이 확인이 있어야 같은 결제의 환불이 PG 호출까지 한 번에 하나씩 처리된다.
     * 건너뛴 환불은 상태가 그대로라 앞선 환불이 끝난 뒤 스케줄러가 처리한다.
     *
     * <p>PG 취소 가능 금액은 결제 금액에서 PG에 반영된(COMPLETED) 환불 금액만 뺀 값이다.
     * 같은 결제의 환불이 순서대로 처리되므로 이 값이 PG의 현재 취소 가능 금액과 같다.
     *
     * @return 점유했으면 PG 취소에 필요한 값
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<ClaimedRefund> claim(Long refundId, Instant now) {
        Payment payment = getPaymentWithLock(refundId);
        Refund refund = getWithLock(refundId);

        if (!refund.isProcessable(now, STALE_PROCESSING_TIMEOUT)) {
            return Optional.empty();
        }

        if (refundRepository.existsByPaymentIdAndStatusAndIdNot(payment.getId(), RefundStatus.PROCESSING, refundId)) {
            return Optional.empty();
        }

        refund.markAsProcessing();
        long completedAmount = refundRepository.sumRefundAmountByPaymentIdAndStatus(
                payment.getId(), RefundStatus.COMPLETED);

        return Optional.of(new ClaimedRefund(
                refund.getId(),
                payment.getPgId(),
                (long) refund.getRefundAmount(),
                refund.getReason(),
                payment.getAmount() - completedAmount
        ));
    }

    /**
     * PG 취소 성공을 기록한다. 환불 COMPLETED와 결제·주문 상태를 한 트랜잭션에서 바꾼다.
     * 모든 주문 상품이 환불됐으면 REFUNDED, 아니면 PARTIALLY_REFUNDED가 된다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordComplete(Long refundId, Instant now) {
        Payment payment = getPaymentWithLock(refundId);
        Refund refund = getWithLock(refundId);
        refund.markAsComplete(now);
        updatePaymentAndOrderStatus(payment);
    }

    /**
     * PG에서 취소된 누적 금액으로 환불 상태를 맞춘다. 취소 웹훅 처리에서 사용한다.
     *
     * <p>{@code PG 취소 금액 − 완료 환불 합계}가 0이면 이미 맞다. 이 차이와 금액이 같은 미완료 환불
     * (PROCESSING·FAILED·DEAD)이 정확히 1건이면 PG에 반영된 것으로 보고 완료 처리한다.
     * REQUESTED는 아직 PG에 보낸 적이 없으므로 후보가 아니다. 그 밖의 경우는 자동으로 맞추지 않는다.
     *
     * <p>결과 기록과 같이 결제를 먼저 잠그므로, 같은 환불의 완료 기록과 겹치면 한쪽만 완료한다.
     *
     * @return ALREADY_DONE(이미 맞음), REFUND_SYNCED(1건 완료), REFUND_MISMATCH(수동 확인 필요)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentSyncResult syncWithPgCancelledAmount(String pgId, long pgCancelledAmount, Instant now) {
        Payment payment = paymentRepository.findWithLockByPgId(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        long completedAmount = refundRepository.sumRefundAmountByPaymentIdAndStatus(
                payment.getId(), RefundStatus.COMPLETED);
        long unsyncedAmount = pgCancelledAmount - completedAmount;

        if (unsyncedAmount == 0) {
            return PaymentSyncResult.ALREADY_DONE;
        }
        if (unsyncedAmount < 0 || unsyncedAmount > Integer.MAX_VALUE) {
            return PaymentSyncResult.REFUND_MISMATCH;
        }

        List<Long> candidateIds = refundRepository.findIdsByPaymentIdAndStatusInAndRefundAmount(
                payment.getId(), PG_SYNC_TARGET_STATUSES, (int) unsyncedAmount);
        if (candidateIds.size() != 1) {
            return PaymentSyncResult.REFUND_MISMATCH;
        }

        Refund refund = getWithLock(candidateIds.get(0));
        refund.markAsCompleteByPg(now);
        updatePaymentAndOrderStatus(payment);
        return PaymentSyncResult.REFUND_SYNCED;
    }

    private void updatePaymentAndOrderStatus(Payment payment) {
        Order order = payment.getOrder();
        if (isAllRefunded(order)) {
            payment.markAsRefunded();
            order.markAsRefunded();
        } else {
            payment.markAsPartiallyRefunded();
            order.markAsPartiallyRefunded();
        }

        // TODO: 재고 복구 필요
    }

    /**
     * 실패를 기록하고 백오프 후 재시도를 예약한다. 최대 시도 횟수를 넘기면 DEAD가 된다.
     *
     * @return 기록 후 상태 (FAILED 또는 DEAD)
     */
    @Transactional
    public RefundStatus recordFailure(Long refundId, String error, Instant now) {
        Refund refund = getWithLock(refundId);
        refund.markAsFailed(error, now);
        return refund.getStatus();
    }

    /**
     * 재시도하면 안 되는 실패를 기록한다. 시도 횟수와 관계없이 DEAD가 된다.
     */
    @Transactional
    public void recordDead(Long refundId, String error) {
        Refund refund = getWithLock(refundId);
        refund.markAsDead(error);
    }

    // 이번 환불의 COMPLETED가 자동 flush된 뒤 합산되므로 이번 환불 수량도 포함된다.
    private boolean isAllRefunded(Order order) {
        List<OrderItem> orderItems = order.getOrderItems();
        Map<Long, Long> completedQuantities = refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(
                        orderItems.stream().map(OrderItem::getId).collect(Collectors.toSet()),
                        List.of(RefundStatus.COMPLETED))
                .stream()
                .collect(Collectors.toMap(RefundedQuantity::orderItemId, RefundedQuantity::quantity));

        return orderItems.stream()
                .allMatch(orderItem -> completedQuantities.getOrDefault(orderItem.getId(), 0L) >= orderItem.getQuantity());
    }

    private Payment getPaymentWithLock(Long refundId) {
        Long paymentId = refundRepository.findPaymentIdById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

        return paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private Refund getWithLock(Long refundId) {
        return refundRepository.findWithLockById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
    }
}
