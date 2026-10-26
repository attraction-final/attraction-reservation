package com.example.attractionreservation.domain.payment.port.dto;

import com.example.attractionreservation.domain.payment.port.enums.GatewayCancellationStatus;
import com.example.attractionreservation.domain.payment.port.enums.GatewayPaymentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * @param cancellations PG 취소 내역. 결제 완료 상태에서도 진행 중인 취소 요청이 들어 있을 수 있다. 없으면 빈 목록
 */
public record PaymentGatewayResponse(
        String pgId,
        GatewayPaymentStatus status,
        Long totalAmount,
        Instant paidAt,
        List<PaymentCancellationResponse> cancellations
) {
    public PaymentGatewayResponse {
        cancellations = cancellations == null ? List.of() : List.copyOf(cancellations);
    }

    public boolean isPaid() {
        return status == GatewayPaymentStatus.PAID;
    }

    public boolean isFailed() {
        return status == GatewayPaymentStatus.FAILED;
    }

    public boolean isCancelled() {
        return status == GatewayPaymentStatus.CANCELLED;
    }

    public Optional<PaymentCancellationResponse> findCancellation(String cancellationId) {
        if (cancellationId == null) {
            return Optional.empty();
        }

        return cancellations.stream()
                .filter(cancellation -> cancellationId.equals(cancellation.cancellationId()))
                .findFirst();
    }

    // PG가 접수했지만 아직 끝나지 않은 취소 내역
    public Optional<PaymentCancellationResponse> findRequestedCancellation() {
        return cancellations.stream()
                .filter(cancellation -> cancellation.status() == GatewayCancellationStatus.REQUESTED)
                .findFirst();
    }
}
