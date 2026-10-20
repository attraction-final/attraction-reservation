package com.example.attractionreservation.domain.payment.dto;

import java.time.Instant;

public record PgPaymentResult(
    String pgId,
    int amount,
    Instant approvedAt
) {
}
