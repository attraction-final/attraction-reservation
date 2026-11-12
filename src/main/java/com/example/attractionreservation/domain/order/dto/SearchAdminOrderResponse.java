package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.ticket.entity.Ticket;

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
        List<TicketSummary> tickets,
        String pgId
) {
    public static SearchAdminOrderResponse from(Order order, String pgId, List<Ticket> tickets) {
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
                tickets.stream()
                        .map(TicketSummary::from)
                        .toList(),
                detail.pgId()
        );
    }

    // 관리자가 부분 환불할 티켓을 고를 수 있도록 티켓 ID와 상태를 반환한다.
    // orderItemId는 티켓이 어느 주문 상품(단가)에 속하는지 화면에서 찾는 데 쓴다.
    public record TicketSummary(
            Long ticketId,
            Long orderItemId,
            String name,
            String status,
            LocalDate validDate
    ) {
        public static TicketSummary from(Ticket ticket) {
            return new TicketSummary(
                    ticket.getId(),
                    ticket.getOrderItem().getId(),
                    ticket.getName(),
                    ticket.getStatus().name(),
                    ticket.getValidDate()
            );
        }
    }
}