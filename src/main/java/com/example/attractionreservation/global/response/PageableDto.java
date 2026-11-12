package com.example.attractionreservation.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record PageableDto<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PageableDto<T> from(Page<T> page) {
        return new PageableDto<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
