package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

@RequiredArgsConstructor
public class ReservationCustomRepositoryImpl implements ReservationCustomRepository{

    private final JPAQueryFactory queryFactory;

    @Override
    public boolean existsReservedByUser_IdAndSlot_Id(Long userId, Long slotId, ReservationStatus status) {
        return false;
    }

    @Override
    public boolean existsTimeConflictReservation(Long userId, LocalDateTime startsAt, LocalDateTime endsAt, ReservationStatus status) {
        return false;
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
}
