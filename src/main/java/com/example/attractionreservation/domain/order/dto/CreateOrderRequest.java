package com.example.attractionreservation.domain.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateOrderRequest(

        @NotEmpty(message = "주문 항목은 한 개 이상이어야 합니다.")
        List<@NotNull @Valid Item> items
) {

    public record Item(

            @NotNull(message = "티켓 상품 ID는 필수입니다.")
            @Min(value = 1, message = "티켓 상품 ID는 1 이상이어야 합니다.")
            Long ticketProductId,

            @NotNull(message = "구매 수량은 필수입니다.")
            @Min(value = 1, message = "구매 수량은 1 이상이어야 합니다.")
            Integer quantity
    ) {
    }
}