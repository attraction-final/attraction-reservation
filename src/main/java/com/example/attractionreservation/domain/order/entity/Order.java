package com.example.attractionreservation.domain.order.entity;

import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    private Order(User user, int totalAmount, LocalDate visitDate) {
        this.user = user;
        this.totalAmount = totalAmount;
        this.status = OrderStatus.PENDING;
        this.visitDate = visitDate;
    }

    public static Order create(User user, int totalAmount, LocalDate visitDate) {
        return new Order(user, totalAmount, visitDate);
    }

    public void markAsPaid() {
        if (!this.status.canTransitTo(OrderStatus.COMPLETED)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS_TRANSITION);
        }

        this.status = OrderStatus.COMPLETED;
    }

    public void markAsCancelled() {
        if (!this.status.canTransitTo(OrderStatus.CANCELLED)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS_TRANSITION);
        }

        this.status = OrderStatus.CANCELLED;
    }

    public void markAsPartiallyRefunded() {
        if (!this.status.canTransitTo(OrderStatus.PARTIALLY_REFUNDED)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS_TRANSITION);
        }

        this.status = OrderStatus.PARTIALLY_REFUNDED;
    }

    public void markAsRefunded() {
        if (!this.status.canTransitTo(OrderStatus.REFUNDED)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_STATUS_TRANSITION);
        }

        this.status = OrderStatus.REFUNDED;
    }

    public void addOrderItem(TicketProduct ticketProduct, int quantity) {
        // 주문 항목의 부모 참조와 주문의 항목 목록을 함께 설정한다.
        OrderItem orderItem = OrderItem.create(this, ticketProduct, quantity);
        this.orderItems.add(orderItem);
    }

    // 주문 항목을 상품별 수량으로 합산한다. 같은 상품이 여러 항목이면 수량을 더한다.
    public Map<Long, Integer> getQuantityByTicketProductId() {
        return orderItems.stream()
                .collect(Collectors.toMap(
                        orderItem -> orderItem.getTicketProduct().getId(),
                        OrderItem::getQuantity,
                        Integer::sum
                ));
    }
}
