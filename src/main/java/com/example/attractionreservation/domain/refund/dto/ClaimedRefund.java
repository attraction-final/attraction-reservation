package com.example.attractionreservation.domain.refund.dto;

// 점유한 환불에서 트랜잭션 밖 PG 취소와 로그에 쓰는 값.
public record ClaimedRefund(
        Long refundId,
        String pgId,
        Long amount,
        String reason,
        Long currentCancellableAmount
) {
}
