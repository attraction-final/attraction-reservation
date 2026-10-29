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
    CONCURRENT_UPDATE_CONFLICT(HttpStatus.CONFLICT, "다른 요청에 의해 데이터가 변경되었습니다. 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 에러"),

    // attraction
    ATTRACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "어트랙션을 찾을 수 없습니다."),
    ATTRACTION_NAME_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 어트랙션명입니다."),
    INVALID_ATTRACTION_STATUS(HttpStatus.BAD_REQUEST, "허용되지 않은 운영 상태입니다."),
    ATTRACTION_STATUS_SAME(HttpStatus.BAD_REQUEST, "현재 상태와 같습니다."),
    ATTRACTION_HAS_UPCOMING_RESERVATIONS(HttpStatus.CONFLICT, "이용 예정 예약이 있어 처리할 수 없습니다. 예약을 먼저 처리해 주세요."),
    ATTRACTION_NOT_OPERATING(HttpStatus.CONFLICT, "현재 운영 중인 어트랙션이 아닙니다."),

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
    SLOT_RESERVATION_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "예약 가능한 슬롯 상태가 아닙니다."),
    SLOT_CAPACITY_ACQUISITION_FAILED(HttpStatus.CONFLICT, "슬롯이 마감되었거나 잔여 정원이 없어 예약할 수 없습니다."),

    // user
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    USER_NOT_WITHDRAWABLE(HttpStatus.FORBIDDEN, "현재 회원 상태에서는 탈퇴할 수 없습니다."),
    USER_WITHDRAWAL_UNUSED_TICKET(HttpStatus.CONFLICT, "미사용 입장권이 있어 탈퇴할 수 없습니다. 환불 후 다시 시도해주세요."),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 일치하지 않습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "유저를 찾을 수 없습니다."),
    USER_STATUS_SAME(HttpStatus.CONFLICT, "현재 회원 상태와 동일합니다."),
    USER_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 회원 상태 변경입니다."),

    // auth
    INVALID_LOGIN_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_ACTIVE(HttpStatus.FORBIDDEN, "이용할 수 없는 회원 상태입니다."),

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_ACCESS_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 Access Token입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료된 Refresh Token입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    SOCIAL_LOGIN_CODE_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않거나 만료되었거나 이미 사용된 로그인 코드입니다."),

    // ticketProduct
    INVALID_TICKET_PRODUCT_PRICE(HttpStatus.BAD_REQUEST, "티켓 상품 가격은 0원 이상이어야 합니다."),
    INVALID_TICKET_PRODUCT_STOCK(HttpStatus.BAD_REQUEST, "티켓 상품 재고는 0개 이상이어야 합니다."),
    INVALID_TICKET_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "올바른 티켓 상품 이름을 입력해주세요."),
    INVALID_TICKET_PRODUCT_DESCRIPTION(HttpStatus.BAD_REQUEST, "올바른 티켓 상품 설명을 입력해주세요."),
    INVALID_TICKET_PRODUCT_TYPE(HttpStatus.BAD_REQUEST, "티켓 상품의 타입이 올바르지 않습니다."),
    INVALID_TICKET_PRODUCT_STATUS(HttpStatus.BAD_REQUEST, "티켓 상품의 상태값이 올바르지 않습니다."),
    TICKET_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "티켓 상품을 찾을 수 없습니다."),
    TICKET_PRODUCT_ALREADY_DELETED(HttpStatus.CONFLICT, "삭제된 티켓 상품은 수정할 수 없습니다."),
    TICKET_PRODUCT_STOCK_INSUFFICIENT(HttpStatus.CONFLICT, "티켓 상품 재고가 부족합니다."),

    // order
    ORDER_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 상태 전이입니다."),
    ORDER_PRODUCT_NOT_ON_SALE(HttpStatus.CONFLICT, "판매 중인 티켓 상품만 주문할 수 있습니다."),
    ORDER_AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "주문 금액이 허용 범위를 초과했습니다."),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    ORDER_CANCEL_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 주문만 취소할 수 있습니다."),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "결제 대기 상태의 주문만 취소할 수 있습니다."),
    ORDER_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 주문만 조회할 수 있습니다."),
    ORDER_INVALID_VISIT_DATE(HttpStatus.BAD_REQUEST, "방문일은 오늘부터 30일 이내에서 선택해야 합니다."),

    // ticket
    INVALID_TICKET_VALID_DATE(HttpStatus.BAD_REQUEST, "입장권 방문일은 필수입니다."),
    INVALID_TICKET_ORDER_ITEM(HttpStatus.BAD_REQUEST, "입장권의 주문 항목은 필수입니다."),
    INVALID_TICKET_SORT(HttpStatus.BAD_REQUEST, "지원하지 않는 티켓 정렬 기준입니다."),
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "티켓을 찾을 수 없습니다."),
    TICKET_ENTRY_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "입장 가능한 티켓 상태가 아닙니다."),
    TICKET_ENTRY_DATE_MISMATCH(HttpStatus.BAD_REQUEST, "티켓의 방문일이 아닙니다."),
    TICKET_ENTRY_TIME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "해당 티켓의 입장 가능 시간이 아닙니다."),
    TICKET_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 티켓만 사용할 수 있습니다."),
    TICKET_RESERVATION_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "예약에 사용할 수 없는 티켓 상태입니다."),
    TICKET_RESERVATION_DATE_MISMATCH(HttpStatus.BAD_REQUEST, "티켓 방문일과 슬롯 날짜가 일치하지 않습니다."),
    TICKET_RESERVATION_TIME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "티켓 이용 가능 시간에 포함되지 않는 슬롯입니다."),
    INVALID_TICKET_ENTRY_TIME(HttpStatus.BAD_REQUEST, "입장 처리 시각은 필수입니다."),
    INVALID_TICKET_EXPIRATION_TIME(HttpStatus.BAD_REQUEST, "티켓 종료 처리 기준 시각은 필수입니다."),
    INVALID_TICKET_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 상태 변경입니다."),
    TICKET_NOT_REFUNDABLE(HttpStatus.CONFLICT, "미사용 티켓만 환불할 수 있습니다."),

    // routeRecommendation
    ROUTE_RECOMMENDATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 추천 경로입니다."),
    NOT_ROUTE_OWNER(HttpStatus.FORBIDDEN, "해당 추천 경로에 접근할 권한이 없습니다."),
    INVALID_ATTRACTION_SELECTION(HttpStatus.BAD_REQUEST, "어트랙션은 중복 없이 1~5개 선택해야 합니다."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "이용 시작 시각은 종료 시각보다 이전이어야 하며 입장권 이용 시간 안이어야 합니다."),
    TICKET_NOT_OWNED(HttpStatus.FORBIDDEN, "본인 소유의 입장권이 아닙니다."),
    TICKET_NOT_AVAILABLE(HttpStatus.CONFLICT, "사용 중인 입장권으로만 추천받을 수 있습니다."),
    ROUTE_RECOMMENDATION_BLOCKED(HttpStatus.CONFLICT, "기존 예약의 취소 가능 시간이 지나 새로운 추천 경로를 생성할 수 없습니다."),

    // reservation
    INVALID_RESERVATION_USER(HttpStatus.BAD_REQUEST, "예약 회원 정보가 필요합니다."),
    INVALID_RESERVATION_TICKET(HttpStatus.BAD_REQUEST, "예약에 사용할 티켓 정보가 필요합니다."),
    INVALID_RESERVATION_SLOT(HttpStatus.BAD_REQUEST, "예약할 슬롯 정보가 필요합니다."),
    RESERVATION_DEADLINE_PASSED(HttpStatus.CONFLICT, "슬롯 시작 30분 전까지만 예약할 수 있습니다."),
    RESERVATION_DUPLICATED(HttpStatus.CONFLICT, "이미 예약한 슬롯입니다."),
    RESERVATION_TIME_CONFLICT(HttpStatus.CONFLICT, "다른 예약과 탑승 시간이 겹칩니다."),
    NOT_FOUND_RESERVATION(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    RESERVATION_CANCEL_NOT_ALLOWED_STATUS(HttpStatus.CONFLICT, "예약 완료 상태에서만 취소할 수 있습니다."),
    RESERVATION_CANCEL_DEADLINE_PASSED(HttpStatus.CONFLICT, "슬롯 시작 30분 전까지만 예약을 취소할 수 있습니다."),
    RESERVATION_CAPACITY_RESTORE_FAILED(HttpStatus.CONFLICT, "슬롯 예약 인원을 복구할 수 없어 예약 취소에 실패했습니다."),
    INVALID_RESERVATION_CANCEL_REASON(HttpStatus.BAD_REQUEST, "예약 취소 사유는 필수입니다."),
    RESERVATION_STATUS_CHANGE_NOT_ALLOWED(HttpStatus.CONFLICT, "예약 완료 상태에서만 이용 완료 또는 노쇼로 변경할 수 있습니다."),
    INVALID_RESERVATION_TARGET_STATUS(HttpStatus.BAD_REQUEST, "변경할 예약 상태는 이용 완료 또는 노쇼만 가능합니다."),
    RESERVATION_SLOT_NOT_ENDED(HttpStatus.CONFLICT, "슬롯 종료 후에만 이용 완료 또는 노쇼로 변경할 수 있습니다."),

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
    PAYMENT_CANCELLABLE_AMOUNT_MISMATCH(HttpStatus.CONFLICT, "결제의 취소 가능 금액이 일치하지 않습니다."),
    PAYMENT_PG_FAILED(HttpStatus.CONFLICT, "결제가 실패했습니다. 결제 수단을 확인한 뒤 다시 결제해 주세요."),
    PAYMENT_WEBHOOK_INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "웹훅 서명이 유효하지 않습니다."),
    PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 웹훅 처리 상태 전이입니다."),
    PAYMENT_WEBHOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "웹훅 수신 기록을 찾을 수 없습니다."),
    PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 보상 처리 상태 전이입니다."),
    PAYMENT_COMPENSATION_NOT_FOUND(HttpStatus.NOT_FOUND, "보상 요청을 찾을 수 없습니다."),

    // refund
    REFUND_INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "유효하지 않은 환불 상태 전이입니다."),
    REFUND_INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "환불 수량이 올바르지 않습니다."),
    REFUND_NOT_REFUNDABLE_PAYMENT(HttpStatus.CONFLICT, "환불할 수 없는 결제 상태입니다."),
    REFUND_INVALID_ORDER_ITEM(HttpStatus.BAD_REQUEST, "해당 주문의 주문 상품이 아닙니다."),
    REFUND_DUPLICATE_ORDER_ITEM(HttpStatus.BAD_REQUEST, "같은 주문 상품을 중복해서 환불할 수 없습니다."),
    REFUND_INVALID_TICKET(HttpStatus.BAD_REQUEST, "해당 주문의 티켓이 아닙니다."),
    REFUND_DUPLICATE_TICKET(HttpStatus.BAD_REQUEST, "같은 티켓을 중복해서 환불할 수 없습니다."),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "환불 내역을 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
