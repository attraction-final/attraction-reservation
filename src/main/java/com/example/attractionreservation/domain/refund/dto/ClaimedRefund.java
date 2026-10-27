package com.example.attractionreservation.domain.refund.dto;

import java.time.Instant;

// 점유하거나 확인 대상으로 꺼낸 환불에서 트랜잭션 밖 PG 호출과 로그에 쓰는 값.
// completedAmount는 PG에 반영된(COMPLETED) 같은 결제의 환불 합계로, PG 취소 누적 금액과 비교해 이 환불이 반영됐는지 판단한다.
// attemptCount는 점유할 때의 시도 횟수, pgPendingSince는 확인 대상으로 꺼낼 때의 PG_PENDING 시작 시각으로,
// 결과를 기록할 때 이 처리가 아직 같은 점유·같은 확인 주기인지 확인하는 데 쓴다.
public record ClaimedRefund(
        Long refundId,
        String pgId,
        Long amount,
        String reason,
        Long currentCancellableAmount,
        Long completedAmount,
        int attemptCount,
        String pgCancellationId,
        Instant pgPendingSince
) {
}
