package com.example.attractionreservation.domain.order.entity;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    private Order(User user, int totalAmount) {
        this.user = user;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
    }

    public static Order create(User user, int totalAmount) {
        return new Order(user, totalAmount);
    }

    public void addOrderItem(TicketProduct ticketProduct, int quantity) {
        // 주문 항목의 부모 참조와 주문의 항목 목록을 함께 설정한다.
        OrderItem orderItem = OrderItem.create(this, ticketProduct, quantity);
        this.orderItems.add(orderItem);
    }
}
