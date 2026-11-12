package com.example.attractionreservation.domain.reservation.dto;


import org.springframework.data.domain.Page;

import java.util.List;

public record SearchReservationListResponse(
        List<SearchReservationResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public static SearchReservationListResponse from(Page<SearchReservationResponse> page) {
        return new SearchReservationListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
