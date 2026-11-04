package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record SearchOrderResponse(
        Long orderId,
        Integer totalAmount,
        String status,
        LocalDateTime createdAt,
        List<Item> items,
        String pgId
) {

    public static SearchOrderResponse from(Order order, String pgId) {
        return new SearchOrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus().name(),
                LocalDateTime.ofInstant(
                        order.getCreatedAt(),
                        ZoneId.of("Asia/Seoul")
                ),
                order.getOrderItems().stream()
                        .map(Item::from)
                        .toList(),
                pgId
        );
    }

    public record Item(
            Long id,
            Long ticketProductId,
            Integer price,
            Integer quantity
    ) {

        public static Item from(OrderItem orderItem) {
            return new Item(
                    orderItem.getId(),
                    orderItem.getTicketProduct().getId(),
                    // 현재 상품 가격이 아닌 주문 당시 단가를 반환한다.
                    orderItem.getPrice(),
                    orderItem.getQuantity()
            );
        }
    }
}