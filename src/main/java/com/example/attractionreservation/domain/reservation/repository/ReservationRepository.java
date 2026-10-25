package com.example.attractionreservation.domain.reservation.repository;

import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {
    @Query("""
        select r from Reservation r
        join fetch r.attractionSlot
        where r.ticket.id = :ticketId
          and r.status = :status
        """)
    List<Reservation> findAllWithSlotByTicketIdAndStatus(@Param("ticketId") Long ticketId,
                                                         @Param("status") ReservationStatus status);
}
