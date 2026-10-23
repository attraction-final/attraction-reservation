package com.example.attractionreservation.domain.attractionSlot.repository;

import java.time.LocalDateTime;

// 목록 조회의 nextSlot 만들 때 사용
// nextSlot: 이 어트랙션에서 오늘 지금 바로 예약할 수 있는 가장 빠른 회차
public interface ReservableSlotView {

    Long getSlotId();

    Long getAttractionId();

    LocalDateTime getStartsAt();

    Integer getRemainingCount();
}
