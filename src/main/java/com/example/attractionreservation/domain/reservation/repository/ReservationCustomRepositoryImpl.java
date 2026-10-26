package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.example.attractionreservation.domain.order.entity.QOrderItem.orderItem;
import static com.example.attractionreservation.domain.reservation.entity.QReservation.reservation;
import static com.example.attractionreservation.domain.ticket.entity.QTicket.ticket;

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


    // 내 예약 목록 조회
    @Override
    public Page<Reservation> findReservationAllByCondition(
            Long userId,
            ReservationStatus status,
            LocalDateTime startDay,
            LocalDateTime nextDay,
            Pageable stablePageable
    ) {
        List<Reservation> content = queryFactory
                .selectFrom(reservation)
                .where(
                        ownerIdEq(userId),
                        statusEq(status),
                        startDayGoe(startDay),
                        nextDayLt(nextDay)
                )
                .orderBy(toOrderSpecifiers(stablePageable.getSort()))
                .offset(stablePageable.getOffset())
                .limit(stablePageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(reservation.count())
                .from(reservation)
                .where(
                        ownerIdEq(userId),
                        statusEq(status),
                        startDayGoe(startDay),
                        nextDayLt(nextDay)
                )
                .fetchOne();
        return new PageImpl<>(
                content,
                stablePageable,
                total != null ? total : 0L
        );
    }

    // 내 예약 상세 조회
    @Override
    public Optional<Reservation> findReservationByIdAndUser_Id(
            Long reservationId,
            Long userId
    ) {
        Reservation result = queryFactory
                .selectFrom(reservation)
                .where(
                        reservationIdEq(reservationId),
                        ownerIdEq(userId)
                )
                .fetchOne();
        return Optional.ofNullable(result);
    }

    // 관리자 예약 목록 조회
    @Override
    public Page<Reservation> findAdminReservationByCondition(Long userId, Long attractionId, ReservationStatus status, LocalDateTime startDay, LocalDateTime nextDay, Pageable stablePageable) {
        return null;
    }

    private BooleanExpression reservationIdEq(Long reservationId) {
        return reservation.id.eq(reservationId);
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

    private BooleanExpression startDayGoe(LocalDateTime startDay) {
        if (startDay == null) {
            return null;
        }
        return reservation.attractionSlot.startsAt.goe(startDay);
    }

    private BooleanExpression nextDayLt(LocalDateTime nextDay) {
        if (nextDay == null) {
            return null;
        }
        return reservation.attractionSlot.startsAt.lt(nextDay);
    }

    private OrderSpecifier<?> toOrderSpecifier(Sort.Order sortOrder) {
        Order direction = sortOrder.isAscending() ? Order.ASC : Order.DESC;

        return switch (sortOrder.getProperty()) {
            case "id" ->
                    new OrderSpecifier<>(direction, reservation.id);
            case "status" ->
                    new OrderSpecifier<>(direction, reservation.status);
            case "cancelReason" ->
                    new OrderSpecifier<>(direction, reservation.cancelReason);
            case "cancelledAt" ->
                    new OrderSpecifier<>(direction, reservation.cancelledAt);
            case "reservationNumber" ->
                    new OrderSpecifier<>(direction, reservation.reservationNumber);
            case "userId" ->
                    new OrderSpecifier<>(direction, reservation.user.id);
            case "ticketId" ->
                    new OrderSpecifier<>(direction, reservation.ticket.id);
            case "slotId" ->
                    new OrderSpecifier<>(direction, reservation.attractionSlot.id);
            case "createdAt" ->
                    new OrderSpecifier<>(direction, reservation.createdAt);
            case "updatedAt" ->
                    new OrderSpecifier<>(direction, reservation.updatedAt);
            default ->
                    throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY);
        };
    }

    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();

        for (Sort.Order sortOrder : sort) {
            orders.add(toOrderSpecifier(sortOrder));
        }

        return orders.toArray(new OrderSpecifier<?>[0]);
    }


}
