package com.example.attractionreservation.domain.ticket.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record SearchTicketListResponse(
        List<SearchTicketResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public static SearchTicketListResponse from(Page<SearchTicketResponse> page) {
        return new SearchTicketListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
