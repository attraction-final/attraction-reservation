package com.example.attractionreservation.domain.ticketproduct.dto;

import com.example.attractionreservation.domain.ticketproduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketproduct.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketproduct.enums.TicketType;

import java.time.Instant;
import java.time.LocalDateTime;

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
                ticketProduct.getId(), ticketProduct.getName(),
                ticketProduct.getType(), ticketProduct.getPrice(),
                ticketProduct.getDescription(), ticketProduct.getStatus(),
                ticketProduct.getStock(),
                ticketProduct.getCreatedAt(), ticketProduct.getUpdatedAt()
        );
    }
}
