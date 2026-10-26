package com.example.attractionreservation.domain.payment.dto;

import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;

// 점유한 보상 요청에서 트랜잭션 밖 PG 취소와 로그에 쓰는 값.
// 보상은 전체 취소(amount = null)로 보내므로 amount는 로그·수동 환불 확인용이다.
public record ClaimedCompensation(
        Long id,
        String pgId,
        Long amount,
        String reason
) {
    public static ClaimedCompensation from(PaymentCompensation compensation) {
        return new ClaimedCompensation(
                compensation.getId(),
                compensation.getPgId(),
                compensation.getAmount(),
                compensation.getReason()
        );
    }
}
