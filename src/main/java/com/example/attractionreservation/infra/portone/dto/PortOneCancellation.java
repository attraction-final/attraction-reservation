package com.example.attractionreservation.infra.portone.dto;

import com.example.attractionreservation.domain.payment.port.dto.PaymentCancellationResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneCancellation(
        String id,
        String status
) {
    public PaymentCancellationResponse toGatewayResponse() {
        return new PaymentCancellationResponse(id, toGatewayStatus(status));
    }

    // PortOne 취소 상태는 REQUESTED·SUCCEEDED·FAILED다. 그 밖의 값은 UNKNOWN으로 변환한다.
    private static GatewayCancellationStatus toGatewayStatus(String status) {
        if (status == null) {
            return GatewayCancellationStatus.UNKNOWN;
        }

        try {
            return GatewayCancellationStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return GatewayCancellationStatus.UNKNOWN;
        }
    }
}
