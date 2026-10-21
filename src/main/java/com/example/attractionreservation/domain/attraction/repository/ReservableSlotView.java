package com.example.attractionreservation.domain.attraction.repository;

import java.time.LocalDateTime;

// attractionSlot entity가 없어 임시로 작성
// 나중에 AttractionSlotRepository 만들어지면 이동할 예정
// 목록 조회의 nextSlot 만들 때 사용
// nextSlot: 이 어트랙션에서 오늘 지금 바로 예약할 수 있는 가장 빠른 회차
public interface ReservableSlotView {

    Long getSlotId();

    Long getAttractionId();

    LocalDateTime getStartsAt();

    Integer getRemainingCount();
}
