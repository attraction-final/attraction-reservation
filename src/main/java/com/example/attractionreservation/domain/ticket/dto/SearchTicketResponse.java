package com.example.attractionreservation.domain.ticket.dto;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

import java.time.LocalDate;

public record SearchTicketResponse(
        Long ticketId,
        String ticketCode,
        String name,
        TicketType type,
        LocalDate validDate,
        TicketStatus status,
        Long orderId,   // 화면의 주문번호 표시용
        String pgId     // 환불 API(/api/payments/{pgId}/refunds/partial) 호출용
) {
    public static SearchTicketResponse from(Ticket ticket, TicketOrderPayment orderPayment) {
        return new SearchTicketResponse(
                ticket.getId(),
                ticket.getCode(),
                ticket.getName(),
                ticket.getType(),
                ticket.getValidDate(),
                ticket.getStatus(),
                orderPayment != null ? orderPayment.orderId() : null,
                orderPayment != null ? orderPayment.pgId() : null
        );
    }
}