package com.example.attractionreservation.domain.ticket.dto;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

import java.time.Instant;
import java.time.LocalDate;

public record CreateTicketEntryResponse(
        Long ticketId,
        String ticketCode,
        TicketType type,
        LocalDate validDate,
        TicketStatus status,
        Instant enteredAt
) {
    public static CreateTicketEntryResponse from(Ticket ticket) {
        return new CreateTicketEntryResponse(
                ticket.getId(),
                ticket.getCode(),
                ticket.getType(),
                ticket.getValidDate(),
                ticket.getStatus(),
                ticket.getEnteredAt()
        );
    }
}
