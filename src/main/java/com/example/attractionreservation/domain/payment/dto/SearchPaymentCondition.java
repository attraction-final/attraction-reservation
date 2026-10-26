package com.example.attractionreservation.domain.payment.dto;

import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;

public record SearchPaymentCondition(
        PaymentStatus paymentStatus
) {
}
