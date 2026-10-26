package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.example.attractionreservation.domain.reservation.entity.QReservation.reservation;

@RequiredArgsConstructor
public class ReservationCustomRepositoryImpl implements ReservationCustomRepository{

    private final JPAQueryFactory queryFactory;

    // 중복 예약 확인
    @Override
    public boolean existsReservedByUser_IdAndSlot_Id(
            Long userId,
            Long slotId,
            ReservationStatus status
    ) {
        return queryFactory
                .selectOne()
                .from(reservation)
                .where(
                        ownerIdEq(userId),
                        slotIdEq(slotId),
                        statusEq(status)
                )
                .fetchFirst() != null;
    }

    // 동일 시간대 예약 여부 확인
    @Override
    public boolean existsTimeConflictReservation(
            Long userId,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            ReservationStatus status
    ) {
        return queryFactory
                .selectOne()
                .from(reservation)
                .where(
                        ownerIdEq(userId),
                        startsAtLt(endsAt),
                        endsAtGt(startsAt),
                        statusEq(status)
                )
                .fetchFirst() != null;
    }


    @Override
    public Page<Reservation> findReservationAllByCondition(Long userId, ReservationStatus status, LocalDateTime startDay, LocalDateTime nextDay, Pageable stablePageable) {
        return null;
    }

    @Override
    public Optional<Reservation> findReservationByIdAndUser_Id(Long reservationId, Long userId) {
        return Optional.empty();
    }

    @Override
    public Page<Reservation> findAdminReservationByCondition(Long userId, Long attractionId, ReservationStatus status, LocalDateTime startDay, LocalDateTime nextDay, Pageable stablePageable) {
        return null;
    }


    private BooleanExpression ownerIdEq(Long userId) {
        return reservation.user.id.eq(userId);
    }

    private BooleanExpression slotIdEq(Long slotId) {
        return reservation.attractionSlot.id.eq(slotId);
    }

    private BooleanExpression statusEq(ReservationStatus status) {
        if (status == null) {
            return null;
        }
        return reservation.status.eq(status);
    }

    private BooleanExpression startsAtLt(LocalDateTime endsAt) {
        if (endsAt == null) {
            return null;
        }
        return reservation.attractionSlot.startsAt.lt(endsAt);
    }

    private BooleanExpression endsAtGt(LocalDateTime startsAt) {
        if (startsAt == null) {
            return null;
        }
        return reservation.attractionSlot.endsAt.gt(startsAt);
    }


}
