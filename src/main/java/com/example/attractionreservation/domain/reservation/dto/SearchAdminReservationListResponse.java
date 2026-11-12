package com.example.attractionreservation.domain.reservation.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record SearchAdminReservationListResponse(
        List<SearchAdminReservationResponse> content,
        int number,
        int size,
        long totalElements,
        int totalPages
) {
    public static SearchAdminReservationListResponse from(Page<SearchAdminReservationResponse> page) {
        return new SearchAdminReservationListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
