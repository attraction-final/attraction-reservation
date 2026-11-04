package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

public record SearchAdminOrderCondition(
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Positive Long userId,
        OrderStatus status
) {
    public SearchAdminOrderCondition {
        page = page == null ? 0 : page;
        size = size == null ? 10 : size;
    }
}