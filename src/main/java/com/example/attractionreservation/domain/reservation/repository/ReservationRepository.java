package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 중복 예약 확인
    @Query("""
    SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
    FROM Reservation r
    WHERE r.user.id = :userId
      AND r.attractionSlot.id = :slotId
      AND r.status = :status
    """)
    boolean existsReservedByUser_IdAndSlot_Id(
            @Param("userId") Long userId,
            @Param("slotId") Long slotId,
            @Param("status") ReservationStatus status
    );

    // 동일 시간대 예약 여부 확인
    @Query("""
    SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END
    FROM Reservation r
    WHERE r.user.id = :userId
      AND r.status = :status
      AND r.attractionSlot.startsAt < :endsAt
      AND r.attractionSlot.endsAt > :startsAt
    """)
    boolean existsTimeConflictReservation(
            @Param("userId") Long userId,
            @Param("startsAt") LocalDateTime startsAt,
            @Param("endsAt") LocalDateTime endsAt,
            @Param("status") ReservationStatus status
    );

    // 내 예약 목록 조회
    @Query("""
    SELECT r
    FROM Reservation r
    WHERE r.user.id = :userId
      AND (:status IS NULL OR r.status = :status)
      AND ((:startDay IS NULL OR r.attractionSlot.startsAt >= :startDay)
        AND (:nextDay IS NULL OR r.attractionSlot.startsAt < :nextDay))
    """)
    Page<Reservation> findReservationAllByCondition(
            @Param("userId") Long userId,
            @Param("status") ReservationStatus status,
            @Param("startDay") LocalDateTime startDay,
            @Param("nextDay") LocalDateTime nextDay,
            Pageable stablePageable
    );

    // 내 예약 상세 조회
    @Query("SELECT r FROM Reservation r WHERE r.user.id = :userId AND r.id = :reservationId")
    Optional<Reservation> findReservationByIdAndUser_Id(@Param("reservationId") Long reservationId, @Param("userId") Long userId);
}
