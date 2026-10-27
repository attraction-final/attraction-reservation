package com.example.attractionreservation.domain.refund.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 부분 환불할 주문 상품과 수량
public record RefundItemRequest(
        @NotNull
        Long orderItemId,

        @NotNull
        @Min(1)
        Integer quantity
) {
}
