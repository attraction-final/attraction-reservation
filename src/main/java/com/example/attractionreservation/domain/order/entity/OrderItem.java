package com.example.attractionreservation.domain.order.entity;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_product_id", nullable = false)
    private TicketProduct ticketProduct;

    @Column(nullable = false)
    private int price; // 주문 시점 가격 스냅샷

    @Column(nullable = false)
    private int quantity;

    private OrderItem(TicketProduct ticketProduct, int price, int quantity) {
        this.ticketProduct = ticketProduct;
        this.price = price;
        this.quantity = quantity;
    }
}
