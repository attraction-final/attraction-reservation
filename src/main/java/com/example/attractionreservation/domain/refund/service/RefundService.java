package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.dto.RefundedQuantity;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.repository.RefundItemRepository;
import com.example.attractionreservation.domain.refund.repository.RefundRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RefundService {
    // 환불 가능 수량에서 빼는 환불 상태.
    // 진행 중(REQUESTED·PROCESSING)과 재시도 대기(FAILED)는 아직 PG 취소될 수 있고,
    // DEAD는 PG에서 취소됐는지 알 수 없으므로 수동으로 확인하기 전까지 포함한다. 결과적으로 모든 상태다.
    private static final List<RefundStatus> OCCUPYING_STATUSES = List.of(RefundStatus.values());

    private final RefundRepository refundRepository;
    private final RefundItemRepository refundItemRepository;
    private final PaymentRepository paymentRepository;

    /**
     * 결제 완료된 주문의 선택한 상품을 환불 요청한다(부분 환불). PG 취소는 하지 않고 REQUESTED로 저장한다.
     *
     * <p>환불 가능 수량은 주문 수량에서 이미 요청된 환불 수량을 뺀 값이다.
     * 결제를 비관적 락으로 조회한 뒤 합계를 구하므로, 같은 결제에 환불 요청이 동시에 들어와도 초과 환불되지 않는다.
     *
     * @param refundQuantities 주문 상품 ID별 환불 수량
     * @return 저장한 환불 ID
     * @throws BusinessException 환불할 상품이 없으면 {@code INVALID_INPUT_VALUE},
     *                           결제가 없거나 다른 사용자의 결제면 {@code PAYMENT_NOT_FOUND},
     *                           결제 완료·부분 환불 상태가 아니면 {@code REFUND_NOT_REFUNDABLE_PAYMENT},
     *                           주문에 없는 상품이면 {@code REFUND_INVALID_ORDER_ITEM},
     *                           수량이 0 이하이거나 환불 가능 수량을 넘으면 {@code REFUND_INVALID_QUANTITY}
     */
    @Transactional
    public Long createRefund(Long userId, String pgId, Map<Long, Integer> refundQuantities,
                             String reason, Instant requestedAt) {
        if (refundQuantities == null || refundQuantities.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Payment payment = getRefundablePayment(userId, pgId);
        Map<Long, OrderItem> orderItems = payment.getOrder().getOrderItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, Long> refundedQuantities = findRefundedQuantities(refundQuantities.keySet());

        Refund refund = Refund.create(payment, reason, requestedAt);
        refundQuantities.forEach((orderItemId, quantity) -> {
            OrderItem orderItem = orderItems.get(orderItemId);
            if (orderItem == null) {
                throw new BusinessException(ErrorCode.REFUND_INVALID_ORDER_ITEM);
            }

            refund.addRefundItem(RefundItem.of(orderItem, quantity, refundableQuantity(orderItem, refundedQuantities)));
        });

        return refundRepository.save(refund).getId();
    }

    /**
     * 결제 완료된 주문의 남은 환불 가능 수량 전부를 환불 요청한다(전체 환불). PG 취소는 하지 않고 REQUESTED로 저장한다.
     * 남은 수량이 없는 상품은 건너뛴다.
     *
     * @return 저장한 환불 ID
     * @throws BusinessException 결제가 없거나 다른 사용자의 결제면 {@code PAYMENT_NOT_FOUND},
     *                           결제 완료·부분 환불 상태가 아니면 {@code REFUND_NOT_REFUNDABLE_PAYMENT},
     *                           환불할 수량이 하나도 없으면 {@code REFUND_INVALID_QUANTITY}
     */
    @Transactional
    public Long createFullRefund(Long userId, String pgId, String reason, Instant requestedAt) {
        Payment payment = getRefundablePayment(userId, pgId);
        List<OrderItem> orderItems = payment.getOrder().getOrderItems();
        Map<Long, Long> refundedQuantities = findRefundedQuantities(
                orderItems.stream().map(OrderItem::getId).collect(Collectors.toSet()));

        Refund refund = Refund.create(payment, reason, requestedAt);
        for (OrderItem orderItem : orderItems) {
            int refundableQuantity = refundableQuantity(orderItem, refundedQuantities);
            if (refundableQuantity > 0) {
                refund.addRefundItem(RefundItem.of(orderItem, refundableQuantity, refundableQuantity));
            }
        }

        if (refund.getRefundItems().isEmpty()) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_QUANTITY);
        }

        return refundRepository.save(refund).getId();
    }

    @Transactional(readOnly = true)
    public RefundResponse findRefundById(Long refundId) {
        return refundRepository.findById(refundId)
                .map(RefundResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
    }

    // 결제를 잠그고 본인 결제인지, 환불할 수 있는 상태인지 확인한다.
    private Payment getRefundablePayment(Long userId, String pgId) {
        Payment payment = paymentRepository.findWithLockByPgId(pgId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        // 다른 사용자의 결제는 존재 여부를 숨기기 위해 없는 결제와 같이 응답한다.
        if (!payment.getOrder().getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);
        }

        if (payment.getStatus() != PaymentStatus.PAID && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new BusinessException(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT);
        }

        return payment;
    }

    private Map<Long, Long> findRefundedQuantities(Set<Long> orderItemIds) {
        return refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(orderItemIds, OCCUPYING_STATUSES)
                .stream()
                .collect(Collectors.toMap(RefundedQuantity::orderItemId, RefundedQuantity::quantity));
    }

    private int refundableQuantity(OrderItem orderItem, Map<Long, Long> refundedQuantities) {
        return orderItem.getQuantity() - refundedQuantities.getOrDefault(orderItem.getId(), 0L).intValue();
    }
}
