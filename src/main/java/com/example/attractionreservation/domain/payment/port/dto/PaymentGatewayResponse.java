package com.example.attractionreservation.domain.payment.port.dto;

import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;

import java.time.Instant;

public record PaymentGatewayResponse(
        String pgId,
        GatewayPaymentStatus status,
        Long totalAmount,
        Instant paidAt
) {
    public boolean isPaid() {
        return status == GatewayPaymentStatus.PAID;
    }

    public boolean isFailed() {
        return status == GatewayPaymentStatus.FAILED;
    }
}
