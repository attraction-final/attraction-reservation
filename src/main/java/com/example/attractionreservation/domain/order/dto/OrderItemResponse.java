package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.OrderItem;

public record OrderItemResponse(
    Long orderItemId,
    Long ticketProductId,
    String ticketProductName,
    int price,
    int quantity
) {
    public static OrderItemResponse from(OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getId(),
                orderItem.getTicketProduct().getId(),
                orderItem.getTicketProduct().getName(),
                orderItem.getPrice(),
                orderItem.getQuantity()
        );
    }
}
