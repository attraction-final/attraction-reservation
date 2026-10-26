package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketCustomRepository {

    @Query("SELECT t FROM Ticket t WHERE t.code = :code")
    Optional<Ticket> findByCode(@Param("code") String code);

    // 내 티켓 상세 조회
    @Query("""
    SELECT t
    FROM Ticket t
    WHERE t.id = :ticketId AND t.orderItem.order.user.id = :userId
    """)
    Optional<Ticket> findByIdAndOwner_Id(@Param("ticketId") Long ticketId, @Param("userId") Long userId);
}
