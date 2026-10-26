package com.example.attractionreservation.domain.ticketProduct.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record SearchTicketProductListResponse(
        List<SearchTicketProductResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {

    public static SearchTicketProductListResponse from(
            Page<SearchTicketProductResponse> page
    ) {
        return new SearchTicketProductListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}