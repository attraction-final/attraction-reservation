package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderCancelProcessor {

    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final TicketProductService ticketProductService;

    // 주문마다 독립된 트랜잭션으로 처리한다.
    // 이 주문의 처리가 실패해도 다른 주문의 변경은 롤백하지 않는다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean cancelExpiredOrder(
            Long orderId,
            Long userId,
            Instant now
    ) {
        // 재고 차감을 위해서 Order를 가져온다
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        Map<Long, Integer> quantitiesByProductId = order.getQuantityByTicketProductId();

        // 결제 승인 경로와 동일하게 결제 행을 먼저 잠근다.
        paymentService.lockPaymentByOrderIdIfExists(orderId);

        int updatedCount = orderRepository.cancelPendingOrder(
                orderId,
                userId,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                now
        );

        if (updatedCount == 0) {
            return false;
        }

        // 재고 복구
        ticketProductService.updateTicketProductsStockIncrease(quantitiesByProductId);

        // 결제 취소 실패 시 이 주문의 변경도 함께 롤백한다.
        paymentService.cancelledByUser(userId, orderId);

        return true;
    }
}