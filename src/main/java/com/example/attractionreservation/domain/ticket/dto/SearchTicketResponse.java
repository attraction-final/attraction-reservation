package com.example.attractionreservation.domain.ticket.dto;

import com.example.attractionreservation.domain.order.entity.OrderItem;
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
        TicketStatus status
) {
    public static SearchTicketResponse from(Ticket ticket) {
        return new SearchTicketResponse(
                ticket.getId(),
                ticket.getCode(),
                ticket.getName(),
                ticket.getType(),
                ticket.getValidDate(),
                ticket.getStatus()
        );
    }
}
