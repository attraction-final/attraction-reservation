package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

public record SearchOrderResponse(
        Long orderId,
        Integer totalAmount,
        String status,
        LocalDate visitDate,
        LocalDateTime createdAt,
        List<Item> items,
        String pgId
) {

    public static SearchOrderResponse from(Order order, String pgId) {
        return new SearchOrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus().name(),
                // 결제 화면이 새로고침돼도 방문일을 다시 보여 줄 수 있도록 함께 반환한다.
                order.getVisitDate(),
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