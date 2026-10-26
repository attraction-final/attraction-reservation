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

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationCustomRepository {


    // 내 예약 상세 조회
    @Query("SELECT r FROM Reservation r WHERE r.user.id = :userId AND r.id = :reservationId")
    Optional<Reservation> findReservationByIdAndUser_Id(@Param("reservationId") Long reservationId, @Param("userId") Long userId);

    // 관리자 예약 목록 조회
    @Query("""
    SELECT r
    FROM Reservation r
    WHERE (:userId IS NULL OR r.user.id = :userId)
      AND (:attractionId IS NULL OR r.attractionSlot.attraction.id = :attractionId)
      AND (:status IS NULL OR r.status = :status)
      AND ((:startDay IS NULL OR r.attractionSlot.startsAt >= :startDay)
        AND (:nextDay IS NULL OR r.attractionSlot.startsAt < :nextDay))
    """)
    Page<Reservation> findAdminReservationByCondition(
            @Param("userId") Long userId,
            @Param("attractionId") Long attractionId,
            @Param("status") ReservationStatus status,
            @Param("startDay") LocalDateTime startDay,
            @Param("nextDay") LocalDateTime nextDay,
            Pageable stablePageable
    );
}
