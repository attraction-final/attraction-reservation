package com.example.attractionreservation.domain.payment.dto;

import com.example.attractionreservation.domain.order.dto.OrderResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentDetailResponse(
    Long paymentId,
    int amount,
    PaymentStatus status,
    Instant paidAt,
    Instant createdAt,
    OrderResponse order
) {
    public static PaymentDetailResponse from(Payment payment) {
        return new PaymentDetailResponse(
                payment.getId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaidAt(),
                payment.getCreatedAt(),
                OrderResponse.from(payment.getOrder())
        );
    }
}
