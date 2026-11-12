package com.example.attractionreservation.domain.attractionSlot.repository;

import java.time.LocalDateTime;

// 어트랙션 목록 조회의 nextSlot 만들 때 사용
// nextSlot: 이 어트랙션에서 오늘 지금 바로 예약할 수 있는 가장 빠른 회차
// QueryDSL은 Projections.constructor()로 "생성자"를 호출해서 객체를 만들기 때문에
// 인터페이스가 아닌 record로 만든다.
public record ReservableSlotView(
        Long slotId,
        Long attractionId,
        LocalDateTime startsAt,
        Integer remainingCount
) {
}
