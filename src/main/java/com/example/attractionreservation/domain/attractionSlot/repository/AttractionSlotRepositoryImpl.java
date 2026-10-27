package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

import static com.example.attractionreservation.domain.attractionSlot.entity.QAttractionSlot.attractionSlot;
import static com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotPredicates.isReservableSlot;

@RequiredArgsConstructor
public class AttractionSlotRepositoryImpl implements AttractionSlotRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    // 시간이 겹치는 슬롯 개수 (등록용)
    @Override
    public long countOverlappingSlots(Long attractionId, LocalDateTime startsAt, LocalDateTime endsAt) {
        return countOverlapping(attractionId, null, startsAt, endsAt); // 제외할 슬롯 없음
    }

    // 시간이 겹치는 슬롯 개수 (시간 수정용, 자기 자신은 제외)
    @Override
    public long countOverlappingSlotsExcludingSlotId(Long attractionId, Long excludeSlotId,
                                                     LocalDateTime startsAt, LocalDateTime endsAt) {
        return countOverlapping(attractionId, excludeSlotId, startsAt, endsAt);
    }

    // 두 메서드가 같이 쓰는 실제 쿼리
    // 겹침 조건: 기존 시작 < 새 종료  AND  기존 종료 > 새 시작
    private long countOverlapping(Long attractionId, Long excludeSlotId,
                                  LocalDateTime startsAt, LocalDateTime endsAt) {
        Long count = queryFactory
                .select(attractionSlot.count())
                .from(attractionSlot)
                .where(
                        attractionSlot.attraction.id.eq(attractionId),
                        slotIdNotEquals(excludeSlotId),                 // null 이면 조건 무시
                        attractionSlot.status.ne(SlotStatus.CANCELLED), // 취소된 슬롯은 겹침 검사에서 제외
                        attractionSlot.startsAt.lt(endsAt),
                        attractionSlot.endsAt.gt(startsAt)
                )
                .fetchOne();
        return count == null ? 0L : count;
    }

    // 특정 어트랙션의 하루치 슬롯 (시작 시각 빠른 순)
    // includeCancelled = false 이면 취소(CANCELLED)된 슬롯은 뺀다.
    @Override
    public List<AttractionSlot> findSlotsByAttractionIdAndDate(Long attractionId, LocalDateTime dayStart,
                                                               LocalDateTime nextDayStart, boolean includeCancelled) {
        return queryFactory
                .selectFrom(attractionSlot)
                .where(
                        attractionSlot.attraction.id.eq(attractionId),
                        attractionSlot.startsAt.goe(dayStart),
                        attractionSlot.startsAt.lt(nextDayStart),
                        excludeCancelled(includeCancelled)
                )
                .orderBy(attractionSlot.startsAt.asc(), attractionSlot.id.asc())
                .fetch();
    }

    // 여러 어트랙션의 "오늘 예약 가능한 슬롯"을 한 번에 조회 (N+1 방지)
    // 어트랙션별로 시작 시각이 빠른 순서로 정렬되어 나온다.
    @Override
    public List<ReservableSlotView> findReservableSlots(List<Long> attractionIds,
                                                        LocalDateTime reservableFrom,
                                                        LocalDateTime todayEnd) {
        return queryFactory
                .select(Projections.constructor(ReservableSlotView.class,
                        attractionSlot.id,
                        attractionSlot.attraction.id,
                        attractionSlot.startsAt,
                        attractionSlot.capacity.subtract(attractionSlot.reservedCount) // 잔여 정원
                ))
                .from(attractionSlot)
                .where(
                        attractionSlot.attraction.id.in(attractionIds),
                        isReservableSlot(reservableFrom, todayEnd)
                )
                .orderBy(attractionSlot.attraction.id.asc(), attractionSlot.startsAt.asc())
                .fetch();
    }

    // ===== 조건 조각들 =====

    private BooleanExpression slotIdNotEquals(Long excludeSlotId) {
        return excludeSlotId == null ? null : attractionSlot.id.ne(excludeSlotId);
    }

    private BooleanExpression excludeCancelled(boolean includeCancelled) {
        return includeCancelled ? null : attractionSlot.status.ne(SlotStatus.CANCELLED);
    }
}
