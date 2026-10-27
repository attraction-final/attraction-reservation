package com.example.attractionreservation.infra.portone.dto;

import com.example.attractionreservation.domain.payment.port.dto.PaymentGatewayResponse;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOnePaymentResponse(
        String id,
        String status,
        Amount amount,
        Instant paidAt,
        List<PortOneCancellation> cancellations
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(Long total, Long cancelled) {}

    // 응답의 id 대신 요청한 pgId를 사용한다.
    public PaymentGatewayResponse toGatewayResponse(String pgId) {
        return new PaymentGatewayResponse(
                pgId,
                toGatewayStatus(status),
                amount == null ? null : amount.total(),
                amount == null ? null : amount.cancelled(),
                paidAt,
                cancellations == null ? List.of() : cancellations.stream()
                        .map(PortOneCancellation::toGatewayResponse)
                        .toList()
        );
    }

    // 지원하지 않는 상태(예: 가상계좌 발급)는 UNKNOWN으로 변환한다.
    private static GatewayPaymentStatus toGatewayStatus(String status) {
        if (status == null) {
            return GatewayPaymentStatus.UNKNOWN;
        }

        try {
            return GatewayPaymentStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return GatewayPaymentStatus.UNKNOWN;
        }
    }
}
