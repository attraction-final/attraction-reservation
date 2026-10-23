package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

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

}
