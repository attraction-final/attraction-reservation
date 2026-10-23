package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, unique = true, length = 40)
    private String pgId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    private Instant paidAt;

    private Payment(Order order) {
        this.order = order;
        this.pgId = UUID.randomUUID().toString();
        this.amount = order.getTotalAmount();
        this.status = PaymentStatus.PENDING;
    }

    public static Payment create(Order order) {
        if (order.getTotalAmount() <= 0) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_AMOUNT);
        }

        return new Payment(order);
    }

    public void markAsPaid() {
        if (!this.status.canTransitTo(PaymentStatus.PAID)) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION);
        }

        this.paidAt = Instant.now();
        this.status = PaymentStatus.PAID;
    }

    public void markAsCancelled() {
        if (!this.status.canTransitTo(PaymentStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION);
        }

        this.status = PaymentStatus.CANCELLED;
    }

    public void markAsPartiallyRefunded() {
        if (!this.status.canTransitTo(PaymentStatus.PARTIALLY_REFUNDED)) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION);
        }

        this.status = PaymentStatus.PARTIALLY_REFUNDED;
    }

    public void markAsRefunded() {
        if (!this.status.canTransitTo(PaymentStatus.REFUNDED)) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION);
        }

        this.status = PaymentStatus.REFUNDED;
    }

    public void markAsFailed() {
        if (!this.status.canTransitTo(PaymentStatus.FAILED)) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS_TRANSITION);
        }

        this.status = PaymentStatus.FAILED;
    }
}
