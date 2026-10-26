package com.example.attractionreservation.domain.payment.dto;

import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;

import java.time.Instant;

public record ConfirmPaymentResponse(
        Long id,
        Long orderId,
        String pgId,
        int amount,
        PaymentStatus status,
        Instant paidAt
) {
    public static ConfirmPaymentResponse from(Payment payment) {
        return new ConfirmPaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getPgId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaidAt()
        );
    }
}
