package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationCustomRepository {

    @Query("""
            select r from Reservation r
            join fetch r.attractionSlot
            where r.ticket.id = :ticketId
              and r.status = :status
            """)
    List<Reservation> findAllWithSlotByTicketIdAndStatus(@Param("ticketId") Long ticketId,
                                                         @Param("status") ReservationStatus status);

    // 탈퇴 시 취소할 이용 예정 예약을 조회하고 중복 변경을 방지한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT r
            FROM Reservation r
            WHERE r.user.id = :userId
              AND r.status = :status
              AND r.attractionSlot.startsAt > :now
            ORDER BY r.attractionSlot.id ASC, r.id ASC
            """)
    List<Reservation> findUpcomingForUpdate(
            @Param("userId") Long userId,
            @Param("status") ReservationStatus status,
            @Param("now") LocalDateTime now
    );

    // 예약 취소 시 중복 취소 방지를 위한 락
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT r FROM Reservation r
        WHERE r.id = :reservationId
        AND r.user.id = :userId
        """)
    Optional<Reservation> findByIdAndUserIdWithLock(
            @Param("reservationId") Long reservationId,
            @Param("userId") Long userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :reservationId")
    Optional<Reservation> findByIdWithLock(
            @Param("reservationId") Long reservationId
    );
}