package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;

import java.time.LocalDateTime;
import java.util.List;
import java.time.ZoneId;

public record SearchOrderResponse(
        Long orderId,
        Integer totalAmount,
        String status,
        LocalDateTime createdAt,
        List<Item> items
) {

    public static SearchOrderResponse from(Order order) {
        return new SearchOrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus().name(),

                // DB의 생성 시각을 서비스 기준 시간대인 KST로 변환한다.
                LocalDateTime.ofInstant(order.getCreatedAt(), ZoneId.of("Asia/Seoul")),
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
                    // 상품의 현재 가격이 아닌 주문 당시 단가를 반환한다.
                    orderItem.getPrice(),
                    orderItem.getQuantity()
            );
        }
    }
}