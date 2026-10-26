package com.example.attractionreservation.domain.ticket.dto;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

import java.time.Instant;
import java.time.LocalDate;

public record SearchTicketDetailResponse(
        Long ticketId,
        String ticketCode,
        Long ticketProductId,
        String name,
        TicketType type,
        LocalDate validDate,
        TicketStatus status,
        Instant enteredAt
) {
    public static SearchTicketDetailResponse from(Ticket ticket) {
        return new SearchTicketDetailResponse(
                ticket.getId(),
                ticket.getCode(),
                ticket.getOrderItem().getTicketProduct().getId(),
                ticket.getName(),
                ticket.getType(),
                ticket.getValidDate(),
                ticket.getStatus(),
                ticket.getEnteredAt()
        );
    }
}
