package com.example.attractionreservation.domain.payment.dto;

import com.example.attractionreservation.domain.payment.entity.PaymentCompensation;

import java.time.Instant;

// 점유하거나 확인 대상으로 꺼낸 보상 요청에서 트랜잭션 밖 PG 호출과 로그에 쓰는 값.
// 보상은 전체 취소(amount = null)로 보내므로 amount는 로그·수동 환불 확인용이다.
// attemptCount는 점유할 때의 시도 횟수, pgPendingSince는 확인 대상으로 꺼낼 때의 PG_PENDING 시작 시각으로,
// 결과를 기록할 때 이 처리가 아직 같은 점유·같은 확인 주기인지 확인하는 데 쓴다.
public record ClaimedCompensation(
        Long id,
        String pgId,
        Long amount,
        String reason,
        int attemptCount,
        String pgCancellationId,
        Instant pgPendingSince
) {
    public static ClaimedCompensation from(PaymentCompensation compensation) {
        return new ClaimedCompensation(
                compensation.getId(),
                compensation.getPgId(),
                compensation.getAmount(),
                compensation.getReason(),
                compensation.getAttemptCount(),
                compensation.getPgCancellationId(),
                compensation.getPgPendingSince()
        );
    }
}
