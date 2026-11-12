package com.example.attractionreservation.domain.ticketProduct.dto;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;

import java.time.Instant;

public record CreateTicketProductResponse(
        Long id,
        String name,
        TicketType type,
        int price,
        String description,
        TicketProductStatus saleStatus,
        int stock,
        Instant createdAt,
        Instant updatedAt
) {
    public static CreateTicketProductResponse from(TicketProduct ticketProduct) {
        return new CreateTicketProductResponse(
                ticketProduct.getId(),
                ticketProduct.getName(),
                ticketProduct.getType(),
                ticketProduct.getPrice(),
                ticketProduct.getDescription(),
                ticketProduct.getStatus(),
                ticketProduct.getStock(),
                ticketProduct.getCreatedAt(),
                ticketProduct.getUpdatedAt()
        );
    }
}
