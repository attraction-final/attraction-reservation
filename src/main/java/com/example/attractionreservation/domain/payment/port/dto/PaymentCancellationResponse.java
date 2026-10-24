package com.example.attractionreservation.domain.payment.port.dto;

import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;

/**
 * PG 취소 내역.
 *
 * @param cancellationId PG 취소 내역 ID. 이미 취소된 결제를 다시 취소했거나 응답을 해석할 수 없으면 null
 */
public record PaymentCancellationResponse(
        String cancellationId,
        GatewayCancellationStatus status
) {
    public boolean isSucceeded() {
        return status == GatewayCancellationStatus.SUCCEEDED;
    }
}
