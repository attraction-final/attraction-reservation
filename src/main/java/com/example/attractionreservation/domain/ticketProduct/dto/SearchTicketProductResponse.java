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
        TicketProductStatus status,
        int stock,      // 남은 재고 (관리자 화면에서 수정할 때 현재 값을 보여 주려고 추가)
        Long version
) {
    public static SearchTicketProductResponse from(TicketProduct ticketProduct) {
        return new SearchTicketProductResponse(
                ticketProduct.getId(),
                ticketProduct.getName(),
                ticketProduct.getType(),
                ticketProduct.getPrice(),
                ticketProduct.getDescription(),
                ticketProduct.getStatus(),
                ticketProduct.getStock(),
                ticketProduct.getVersion()
        );
    }
}