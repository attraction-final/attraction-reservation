package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record SearchOrderListResponse(
        List<Item> content,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    public static SearchOrderListResponse from(Page<Order> orders) {
        return new SearchOrderListResponse(
                orders.getContent().stream()
                        .map(Item::from)
                        .toList(),
                orders.getNumber(),
                orders.getSize(),
                orders.getTotalElements(),
                orders.getTotalPages()
        );
    }

    public record Item(
            Long orderId,
            Integer totalAmount,
            String status,
            LocalDateTime createdAt
    ) {

        public static Item from(Order order) {
            return new Item(
                    order.getId(),
                    order.getTotalAmount(),
                    order.getStatus().name(),
                    // 상세 조회와 동일하게 생성 시각을 KST로 반환한다.
                    LocalDateTime.ofInstant(
                            order.getCreatedAt(),
                            ZoneId.of("Asia/Seoul")
                    )
            );
        }
    }
}