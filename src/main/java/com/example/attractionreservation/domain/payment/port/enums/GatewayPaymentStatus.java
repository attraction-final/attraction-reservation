package com.example.attractionreservation.domain.payment.port.enums;

public enum GatewayPaymentStatus {
    READY,
    PENDING,
    PAID,
    FAILED,
    CANCELLED,
    PARTIAL_CANCELLED,
    NOT_FOUND,
    UNKNOWN
}
