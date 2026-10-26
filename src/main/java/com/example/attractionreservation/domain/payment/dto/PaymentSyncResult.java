package com.example.attractionreservation.domain.payment.dto;

// PG 결제 상태를 원장에 반영한 결과. 웹훅 처리처럼 예외 대신 결과로 판단해야 하는 곳에서 사용한다.
public enum PaymentSyncResult {
    // 결제 완료로 반영
    APPROVED,
    // 이미 결제 완료 또는 실패로 처리된 결제
    ALREADY_DONE,
    // PG에서 아직 결제 완료가 아님 (READY 등)
    NOT_PAID,
    // PG 결제 실패를 반영
    PG_FAILED,
    // 금액 불일치 또는 원장 취소된 결제라 PG 보상 취소
    COMPENSATED,
    // 우리 원장에 없는 결제
    NOT_OUR_PAYMENT,
    // 원장 상태가 이미 끝나(CANCELLED 등) 반영할 수 없다. 수동 확인이 필요하다.
    STATE_CONFLICT,
    // 결제 완료·환불된 결제라 PG 취소 금액으로 환불 상태를 맞춰야 한다. 웹훅 처리기가 환불 동기화로 이어서 처리하며, 기록되는 결과가 아니다.
    REFUND_SYNC_REQUIRED,
    // PG에 반영된 취소로 미완료 환불을 완료 처리
    REFUND_SYNCED,
    // PG 취소 금액과 환불 기록을 맞출 수 없다. 수동 확인이 필요하다.
    REFUND_MISMATCH,
    // PG가 접수한 환불 취소가 아직 확정되지 않았다. 스케줄러가 조회로 확정한다.
    REFUND_PENDING
}
