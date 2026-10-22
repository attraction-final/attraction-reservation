package com.example.attractionreservation.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // common
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "입력값을 확인해주세요"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST,  "요청 본문 형식이 올바르지 않습니다."),
    MISSING_REQUEST_VALUE(HttpStatus.BAD_REQUEST,  "필수 요청값이 누락되었습니다."),
    URL_NOT_FOUND(HttpStatus.NOT_FOUND,  "요청한 주소를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 콘텐츠 형식입니다."),
    CONCURRENT_UPDATE_CONFLICT(HttpStatus.CONFLICT, "다른 요청에 의해 데이터가 변경되었습니다. 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러"),

    // attraction
    ATTRACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "어트랙션을 찾을 수 없습니다."),
    ATTRACTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 어트랙션명입니다."),
    INVALID_ATTRACTION_STATUS(HttpStatus.BAD_REQUEST, "허용되지 않은 운영 상태입니다."),
    ATTRACTION_STATUS_SAME(HttpStatus.BAD_REQUEST, "현재 상태와 같습니다."),
    ATTRACTION_HAS_UPCOMING_RESERVATIONS(HttpStatus.CONFLICT, "이용 예정 예약이 있어 처리할 수 없습니다. 예약을 먼저 처리해 주세요."),
  
    // user
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    // ticketProduct
    INVALID_TICKET_PRODUCT_PRICE(HttpStatus.BAD_REQUEST, "티켓 상품 가격은 0원 이상이어야 합니다."),
    INVALID_TICKET_PRODUCT_STOCK(HttpStatus.BAD_REQUEST, "티켓 상품 재고는 0개 이상이어야 합니다."),
    INVALID_TICKET_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "올바른 티켓 상품 이름을 입력해주세요."),
    INVALID_TICKET_PRODUCT_DESCRIPTION(HttpStatus.BAD_REQUEST, "올바른 티켓 상품 설명을 입력해주세요."),
    INVALID_TICKET_PRODUCT_TYPE(HttpStatus.BAD_REQUEST, "티켓 상품의 타입이 올바르지 않습니다."),
    INVALID_TICKET_PRODUCT_STATUS(HttpStatus.BAD_REQUEST, "티켓 상품의 상태값이 올바르지 않습니다."),
    TICKET_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "티켓 상품을 찾을 수 없습니다."),
    TICKET_PRODUCT_ALREADY_DELETED(HttpStatus.CONFLICT, "삭제된 티켓 상품은 수정할 수 없습니다."),

    // order
    ORDER_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 상태 전이입니다."),

    //payment
    PAYMENT_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "유효하지 않은 결제 금액입니다."),
    PAYMENT_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 상태 전이입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다."),
    PAYMENT_NOT_PAID(HttpStatus.CONFLICT, "결제사에서 결제가 완료되지 않았습니다."),
    PAYMENT_INCORRECT_AMOUNT(HttpStatus.BAD_REQUEST, "결제 금액이 결제 원장과 일치하지 않습니다."),
    PAYMENT_CANCEL_PROHIBITED(HttpStatus.CONFLICT, "결제 대기 상태가 아닌 결제는 취소할 수 없습니다."),
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "결제사 조회에 실패했습니다."),
    PAYMENT_CANCELLATION_FAILED(HttpStatus.BAD_GATEWAY, "결제 취소에 실패했습니다."),
    PAYMENT_PG_PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PG 결제를 찾을 수 없습니다."),
    PAYMENT_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 결제입니다."),
    PAYMENT_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 결제입니다."),
    PAYMENT_PG_FAILED(HttpStatus.BAD_GATEWAY, "PG 결제에 실패했습니다."),
    PAYMENT_WEBHOOK_INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "웹훅 서명이 유효하지 않습니다."),
    PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 웹훅 처리 상태 전이입니다."),
    PAYMENT_WEBHOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "웹훅 수신 기록을 찾을 수 없습니다."),
    PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 보상 처리 상태 전이입니다."),
    PAYMENT_COMPENSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "보상 요청을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}