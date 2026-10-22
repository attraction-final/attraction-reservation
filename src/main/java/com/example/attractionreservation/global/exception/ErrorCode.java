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
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러"),

    // attraction
    ATTRACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "어트랙션을 찾을 수 없습니다."),
    ATTRACTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 어트랙션명입니다."),
    INVALID_ATTRACTION_STATUS(HttpStatus.BAD_REQUEST, "허용되지 않은 운영 상태입니다."),
    ATTRACTION_STATUS_SAME(HttpStatus.BAD_REQUEST, "현재 상태와 같습니다."),
    ATTRACTION_HAS_UPCOMING_RESERVATIONS(HttpStatus.CONFLICT, "이용 예정 예약이 있어 처리할 수 없습니다. 예약을 먼저 처리해 주세요."),
  
    // user
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // auth
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "이용할 수 없는 회원 상태입니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 Access Token입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

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
    ORDER_PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 티켓 상품만 주문할 수 있습니다."),
    ORDER_AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "주문 금액이 허용 범위를 초과했습니다."),

    //payment
    PAYMENT_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "유효하지 않은 결제 금액입니다."),
    PAYMENT_INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "유효하지 않은 상태 전이입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}