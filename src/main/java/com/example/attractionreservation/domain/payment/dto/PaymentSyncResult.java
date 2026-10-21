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
    // 금액 불일치로 실패 처리하고 PG 보상 취소
    COMPENSATED,
    // 우리 원장에 없는 결제
    NOT_OUR_PAYMENT,
    // PG 보상 취소 실패. PG에는 결제가 남아 있어 수동 환불이 필요하다.
    COMPENSATION_FAILED,
    // 원장 상태가 이미 끝나(CANCELLED 등) 반영할 수 없다. 수동 확인이 필요하다.
    STATE_CONFLICT
}
