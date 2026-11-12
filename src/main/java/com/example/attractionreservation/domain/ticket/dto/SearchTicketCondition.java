package com.example.attractionreservation.domain.ticket.dto;

import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

public record SearchTicketCondition(
        String name,
        TicketType type,
        TicketStatus status
) {
}
