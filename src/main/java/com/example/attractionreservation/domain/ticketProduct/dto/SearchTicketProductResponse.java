package com.example.attractionreservation.domain.ticketProduct.dto;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

public record SearchTicketProductResponse(
        Long ticketProductId,
        String name,
        TicketType type,
        int price,
        String description,
        TicketProductStatus status
) {
    public static SearchTicketProductResponse from(TicketProduct ticketProduct) {
        return new SearchTicketProductResponse(
                ticketProduct.getId(),
                ticketProduct.getName(),
                ticketProduct.getType(),
                ticketProduct.getPrice(),
                ticketProduct.getDescription(),
                ticketProduct.getStatus()
        );
    }
}
