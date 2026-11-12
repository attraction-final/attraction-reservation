package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record SearchAdminOrderListResponse(
        List<Item> content,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {
    public static SearchAdminOrderListResponse from(Page<Order> orders) {
        return new SearchAdminOrderListResponse(
                orders.getContent().stream().map(Item::from).toList(),
                orders.getNumber(),
                orders.getSize(),
                orders.getTotalElements(),
                orders.getTotalPages()
        );
    }

    public record Item(
            Long orderId,
            Long userId,
            String email,
            String nickname,
            Integer totalAmount,
            String status,
            LocalDate visitDate,
            LocalDateTime createdAt
    ) {
        public static Item from(Order order) {
            return new Item(
                    order.getId(),
                    order.getUser().getId(),
                    order.getUser().getEmail(),
                    order.getUser().getNickname(),
                    order.getTotalAmount(),
                    order.getStatus().name(),
                    order.getVisitDate(),
                    LocalDateTime.ofInstant(
                            order.getCreatedAt(),
                            ZoneId.of("Asia/Seoul")
                    )
            );
        }
    }
}