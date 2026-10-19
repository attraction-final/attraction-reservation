package com.example.attractionreservation.domain.payment.entity;

import com.example.attractionreservation.domain.order.entity.Order;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false, unique = true, length = 30)
    private String pgId;

    @Column(nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    private LocalDateTime paidAt;

    private Payment(Order order, int amount) {
        this.order = order;
        this.pgId = UUID.randomUUID().toString();
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    public static Payment create(Order order, int amount) {
        return new Payment(order, amount);
    }
}
