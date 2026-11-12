package com.example.attractionreservation.domain.ticketProduct.dto;

import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

public record SearchTicketProductCondition(
        String name,
        TicketType type,
        TicketProductStatus status
) {
}
