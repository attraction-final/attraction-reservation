package com.example.attractionreservation.domain.refund.dto;

import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;

public record RefundResponse(
        Long refundId,
        int refundAmount,
        RefundStatus status
) {
    public static RefundResponse from(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getRefundAmount(), refund.getStatus());
    }
}
