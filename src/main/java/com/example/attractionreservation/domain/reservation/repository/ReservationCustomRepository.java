package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReservationCustomRepository {

    boolean existsReservedByUser_IdAndSlot_Id(
            Long userId,
            Long slotId,
            ReservationStatus status
    );

    boolean existsTimeConflictReservation(
            Long userId,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            ReservationStatus status
    );

    Page<Reservation> findReservationAllByCondition(
            Long userId,
            ReservationStatus status,
            LocalDateTime startDay,
            LocalDateTime nextDay,
            Pageable stablePageable
    );

    Optional<Reservation> findReservationByIdAndUser_Id(
            Long reservationId,
            Long userId
    );

    Page<Reservation> findAdminReservationByCondition(
            Long userId,
            Long attractionId,
            ReservationStatus status,
            LocalDateTime startDay,
            LocalDateTime nextDay,
            Pageable stablePageable
    );
}
