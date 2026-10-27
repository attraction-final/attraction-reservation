package com.example.attractionreservation.domain.reservation.entity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReservationCancelReason {

    ATTRACTION_MAINTENANCE("어트랙션 점검으로 예약이 취소되었습니다."),
    WEATHER_CONDITION("기상 악화로 예약이 취소되었습니다."),
    SAFETY_CONCERN("안전상의 이유로 예약이 취소되었습니다."),
    OPERATIONAL_ISSUE("운영 사정으로 예약이 취소되었습니다."),
    USER_REQUEST("이용자 요청에 따라 관리자가 예약을 취소했습니다."),
    OTHER("기타 사유로 관리자가 예약을 취소했습니다."),
    USER_WITHDRAWAL("회원 탈퇴로 예약이 취소되었습니다.");

    private final String message;
}