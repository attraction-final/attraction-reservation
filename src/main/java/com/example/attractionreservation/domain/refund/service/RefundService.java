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
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private final TicketService ticketService;

    /**
     * 결제 완료된 주문의 선택한 티켓을 환불 요청한다(부분 환불). PG 취소는 하지 않고 REQUESTED로 저장한다.
     *
     * <p>티켓을 주문 상품별로 묶어 환불 수량으로 만들고, 티켓은 바로 REFUNDED로 바꾼다.
     * 결제 → 티켓 순서로 비관적 락을 잡으므로, 같은 결제나 같은 티켓에 환불 요청이 동시에 들어와도 중복 환불되지 않는다.
     *
     * @param ticketIds 환불할 티켓 ID
     * @return 저장한 환불 ID
     * @throws BusinessException 환불할 티켓이 없으면 {@code INVALID_INPUT_VALUE},
     *                           같은 티켓이 중복되면 {@code REFUND_DUPLICATE_TICKET},
     *                           결제가 없거나 다른 사용자의 결제면 {@code PAYMENT_NOT_FOUND},
     *                           결제 완료·부분 환불 상태가 아니면 {@code REFUND_NOT_REFUNDABLE_PAYMENT},
     *                           없는 티켓이거나 해당 주문의 티켓이 아니면 {@code REFUND_INVALID_TICKET},
     *                           환불 가능 수량을 넘으면 {@code REFUND_INVALID_QUANTITY},
     *                           미사용 티켓이 아니면 {@code TICKET_NOT_REFUNDABLE}
     */
    @Transactional
    public Long createRefund(Long userId, String pgId, List<Long> ticketIds, String reason, Instant requestedAt) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }

        Set<Long> uniqueTicketIds = new HashSet<>(ticketIds);
        if (uniqueTicketIds.size() != ticketIds.size()) {
            throw new BusinessException(ErrorCode.REFUND_DUPLICATE_TICKET);
        }

        Payment payment = getRefundablePayment(userId, pgId);
        List<Ticket> tickets = ticketService.findRefundTicketsForUpdate(orderItemIds(payment), uniqueTicketIds);

        return saveRefund(payment, tickets, reason, requestedAt);
    }

    /**
     * 결제 완료된 주문의 남은 미사용 티켓 전부를 환불 요청한다(전체 환불). PG 취소는 하지 않고 REQUESTED로 저장한다.
     * 입장했거나 이미 환불된 티켓은 건너뛴다.
     *
     * @return 저장한 환불 ID
     * @throws BusinessException 결제가 없거나 다른 사용자의 결제면 {@code PAYMENT_NOT_FOUND},
     *                           결제 완료·부분 환불 상태가 아니면 {@code REFUND_NOT_REFUNDABLE_PAYMENT},
     *                           환불할 미사용 티켓이 하나도 없으면 {@code REFUND_INVALID_QUANTITY}
     */
    @Transactional
    public Long createFullRefund(Long userId, String pgId, String reason, Instant requestedAt) {
        Payment payment = getRefundablePayment(userId, pgId);
        List<Ticket> tickets = ticketService.findUnusedTicketsForUpdate(orderItemIds(payment));

        if (tickets.isEmpty()) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_QUANTITY);
        }

        return saveRefund(payment, tickets, reason, requestedAt);
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

    // 티켓을 주문 상품별 수량으로 묶어 환불 상품을 만들고 티켓을 환불 처리한다.
    // 환불 가능 수량(주문 수량 − 요청된 환불 수량)도 다시 확인해 티켓 상태와 환불 수량이 어긋나지 않게 한다.
    private Long saveRefund(Payment payment, List<Ticket> tickets, String reason, Instant requestedAt) {
        Map<Long, OrderItem> orderItems = payment.getOrder().getOrderItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, Long> ticketCounts = tickets.stream()
                .collect(Collectors.groupingBy(ticket -> ticket.getOrderItem().getId(), TreeMap::new, Collectors.counting()));
        Map<Long, Long> refundedQuantities = findRefundedQuantities(ticketCounts.keySet());

        Refund refund = Refund.create(payment, reason, requestedAt);
        ticketCounts.forEach((orderItemId, count) -> {
            OrderItem orderItem = orderItems.get(orderItemId);
            refund.addRefundItem(RefundItem.of(orderItem, count.intValue(), refundableQuantity(orderItem, refundedQuantities)));
        });

        tickets.forEach(Ticket::refund);

        return refundRepository.save(refund).getId();
    }

    private Set<Long> orderItemIds(Payment payment) {
        return payment.getOrder().getOrderItems().stream()
                .map(OrderItem::getId)
                .collect(Collectors.toSet());
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
