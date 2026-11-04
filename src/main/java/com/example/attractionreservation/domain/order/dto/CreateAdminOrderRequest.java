package com.example.attractionreservation.domain.order.dto;

import com.example.attractionreservation.domain.order.dto.CreateOrderRequest.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

public record CreateAdminOrderRequest(
        @NotNull(message = "주문 대상 회원 ID는 필수입니다.")
        @Positive(message = "주문 대상 회원 ID는 양수여야 합니다.")
        Long userId,

        @NotEmpty(message = "주문 항목은 한 개 이상이어야 합니다.")
        List<@NotNull @Valid Item> items,

        @NotNull(message = "방문일은 필수입니다.")
        LocalDate visitDate
) {
    public CreateOrderRequest toOrderRequest() {
        return new CreateOrderRequest(items, visitDate);
    }
}