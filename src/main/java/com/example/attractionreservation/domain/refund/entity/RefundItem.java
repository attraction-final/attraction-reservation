package com.example.attractionreservation.domain.refund.entity;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id", nullable = false)
    private Refund refund;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @Column(nullable = false)
    private int quantity;

    private RefundItem(OrderItem orderItem, int quantity) {
        this.orderItem = orderItem;
        this.quantity = quantity;
    }

    /**
     * @param refundableQuantity 주문 수량에서 진행 중·완료된 환불 수량을 뺀 값. 서비스가 결제 락 안에서 계산해 넘긴다.
     */
    public static RefundItem of(OrderItem orderItem, int quantity, int refundableQuantity) {
        if (quantity <= 0 || quantity > refundableQuantity) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_QUANTITY);
        }

        return new RefundItem(orderItem, quantity);
    }

    void assignRefund(Refund refund) { // Refund.addRefundItem 에서만 호출
        this.refund = refund;
    }

    public int getRefundPrice() {
        return orderItem.getPrice() * quantity; // 주문 당시 가격 기준
    }
}
