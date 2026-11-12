package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;

public record UpdateOrderResponse(
        Long orderId,
        OrderStatus status
) {

    public static UpdateOrderResponse of(
            Long orderId,
            OrderStatus status
    ) {
        return new UpdateOrderResponse(orderId, status);
    }
}