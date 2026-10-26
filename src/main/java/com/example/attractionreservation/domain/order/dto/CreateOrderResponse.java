package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;

import java.util.List;

public record CreateOrderResponse(
        Long orderId,
        Integer totalAmount,
        String status,
        String pgId,
        List<Item> items
) {

    public static CreateOrderResponse from(Order order, String pgId) {
        return new CreateOrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus().name(),
                pgId,
                order.getOrderItems().stream()
                        .map(Item::from)
                        .toList()
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
                    orderItem.getPrice(),
                    orderItem.getQuantity()
            );
        }
    }
}