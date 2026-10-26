package com.example.attractionreservation.domain.payment.port.dto;

import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;

public record PaymentCancellationResponse(
        String cancellationId,
        GatewayCancellationStatus status
) {
}
