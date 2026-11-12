package com.example.attractionreservation.domain.payment.dto;

import jakarta.validation.constraints.NotNull;

public record ConfirmPaymentRequest(
        @NotNull(message = "주문 ID는 필수입니다.")
        Long orderId
) {
}
