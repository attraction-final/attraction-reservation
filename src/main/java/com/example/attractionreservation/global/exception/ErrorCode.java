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
    INVALID_SORT_PROPERTY(HttpStatus.BAD_REQUEST, "지원하지 않는 정렬 조건입니다."),
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

    // attractionSlot
    SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "슬롯을 찾을 수 없습니다."),
    SLOT_OUT_OF_OPERATING_HOURS(HttpStatus.BAD_REQUEST, "공원 운영 시간(10:00~22:00) 밖의 슬롯입니다."),
    SLOT_START_IN_PAST(HttpStatus.BAD_REQUEST, "지난 시각에는 슬롯을 만들 수 없습니다."),
    INVALID_SLOT_STATUS(HttpStatus.BAD_REQUEST, "허용되지 않은 슬롯 상태입니다."),
    SLOT_STATUS_SAME(HttpStatus.BAD_REQUEST, "현재 상태와 같습니다."),
    SLOT_TIME_OVERLAP(HttpStatus.CONFLICT, "같은 날 다른 슬롯과 시간이 겹칩니다."),
    SLOT_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 종료된 슬롯은 수정·삭제할 수 없습니다."),
    SLOT_ALREADY_CANCELLED(HttpStatus.CONFLICT, "이미 취소된 슬롯입니다."),
    SLOT_HAS_RESERVATIONS(HttpStatus.CONFLICT, "예약이 있는 슬롯입니다. 기존 예약을 먼저 처리해 주세요."),
    SLOT_CAPACITY_BELOW_RESERVED(HttpStatus.CONFLICT, "예약된 인원보다 적게 정원을 줄일 수 없습니다."),
    SLOT_FULL(HttpStatus.CONFLICT, "잔여 정원이 없어 OPEN으로 바꿀 수 없습니다."),

    // user
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),

    // auth
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "이용할 수 없는 회원 상태입니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 Access Token입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 Refresh Token입니다."),
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
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 주문만 조회할 수 있습니다."),

    // ticket
    INVALID_TICKET_VALID_DATE(HttpStatus.BAD_REQUEST, "입장권 방문일은 필수입니다."),
    INVALID_TICKET_ORDER_ITEM(HttpStatus.BAD_REQUEST, "입장권의 주문 항목은 필수입니다."),
    INVALID_TICKET_SORT(HttpStatus.BAD_REQUEST, "지원하지 않는 티켓 정렬 기준입니다."),
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "티켓을 찾을 수 없습니다."),
    TICKET_ENTRY_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "입장 가능한 티켓 상태가 아닙니다."),
    TICKET_ENTRY_DATE_MISMATCH(HttpStatus.BAD_REQUEST, "티켓의 방문일이 아닙니다."),
    TICKET_ENTRY_TIME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "해당 티켓의 입장 가능 시간이 아닙니다."),

    //routeRecommendation
    ROUTE_RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 추천 경로입니다"),
    NOT_ROUTE_OWNER(HttpStatus.FORBIDDEN, "해당 추천 경로에 접근할 권한이 없습니다."),

    //payment
    PAYMENT_INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "유효하지 않은 결제 금액입니다."),
    PAYMENT_INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "유효하지 않은 상태 전이입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 내역을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}