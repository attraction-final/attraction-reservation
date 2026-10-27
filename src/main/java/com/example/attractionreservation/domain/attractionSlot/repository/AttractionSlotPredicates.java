package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.querydsl.core.types.dsl.BooleanExpression;

import java.time.LocalDateTime;

import static com.example.attractionreservation.domain.attractionSlot.entity.QAttractionSlot.attractionSlot;

// 슬롯 조회 조건 중 여러 Repository에서 같이 쓰는 것을 모아 둔 곳
// - AttractionSlotRepositoryImpl : nextSlot 조회
// - AttractionRepositoryImpl     : "예약 가능만 보기" 필터 (EXISTS 서브쿼리)
// 규칙이 바뀌면 여기 한 곳만 고치면 된다.
public final class AttractionSlotPredicates {

    private AttractionSlotPredicates() {
    }

    // "지금 예약할 수 있는 오늘 슬롯" 조건
    // - 상태 OPEN
    // - 정원 > 예약 인원 (= 잔여 정원 1 이상)
    // - 예약 마감(시작 30분 전) 전인 슬롯: reservableFrom 이후 시작
    // - 오늘 안에 시작: todayEnd 이전 시작
    public static BooleanExpression isReservableSlot(LocalDateTime reservableFrom, LocalDateTime todayEnd) {
        return attractionSlot.status.eq(SlotStatus.OPEN)
                .and(attractionSlot.capacity.gt(attractionSlot.reservedCount))
                .and(attractionSlot.startsAt.goe(reservableFrom))
                .and(attractionSlot.startsAt.lt(todayEnd));
    }
}
