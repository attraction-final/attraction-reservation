package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SearchAdminOrderResponse(
        Long orderId,
        Long userId,
        String email,
        String nickname,
        Integer totalAmount,
        String status,
        LocalDate visitDate,
        LocalDateTime createdAt,
        List<SearchOrderResponse.Item> items,
        String pgId
) {
    public static SearchAdminOrderResponse from(Order order, String pgId) {
        SearchOrderResponse detail = SearchOrderResponse.from(order, pgId);

        return new SearchAdminOrderResponse(
                detail.orderId(),
                order.getUser().getId(),
                order.getUser().getEmail(),
                order.getUser().getNickname(),
                detail.totalAmount(),
                detail.status(),
                order.getVisitDate(),
                detail.createdAt(),
                detail.items(),
                detail.pgId()
        );
    }
}