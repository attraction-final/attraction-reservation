package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

public record SearchOrderListResponse(
        List<Item> content,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    // orderItemsByOrderId: 주문 ID별 주문 상품 목록. 서비스에서 한 번의 쿼리로 미리 조회해 넘긴다.
    public static SearchOrderListResponse from(
            Page<Order> orders,
            Map<Long, List<OrderItem>> orderItemsByOrderId
    ) {
        return new SearchOrderListResponse(
                orders.getContent().stream()
                        .map(order -> Item.from(
                                order,
                                // 주문 상품이 없는 주문도 목록에서 빠지지 않도록 빈 목록을 기본값으로 쓴다.
                                orderItemsByOrderId.getOrDefault(order.getId(), List.of())
                        ))
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
            LocalDateTime createdAt,
            List<ItemSummary> items
    ) {

        public static Item from(Order order, List<OrderItem> orderItems) {
            return new Item(
                    order.getId(),
                    order.getTotalAmount(),
                    order.getStatus().name(),
                    // 상세 조회와 동일하게 생성 시각을 KST로 반환한다.
                    LocalDateTime.ofInstant(
                            order.getCreatedAt(),
                            ZoneId.of("Asia/Seoul")
                    ),
                    orderItems.stream()
                            .map(ItemSummary::from)
                            .toList()
            );
        }
    }

    // 결제 내역 목록에 보여 줄 주문 상품 요약 (상품 이름과 수량)
    public record ItemSummary(
            Long ticketProductId,
            String ticketProductName,
            Integer quantity
    ) {

        public static ItemSummary from(OrderItem orderItem) {
            return new ItemSummary(
                    orderItem.getTicketProduct().getId(),
                    orderItem.getTicketProduct().getName(),
                    orderItem.getQuantity()
            );
        }
    }
}