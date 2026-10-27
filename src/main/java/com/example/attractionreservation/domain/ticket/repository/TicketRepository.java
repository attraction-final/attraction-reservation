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

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // 내 티켓 목록 조회
    @Query("""
    SELECT t FROM Ticket t
    WHERE t.orderItem.order.user.id = :userId AND (:name IS NULL OR t.name LIKE CONCAT('%', :name, '%'))
        AND (:type IS NULL OR t.type = :type)
        AND (:status IS NULL OR t.status = :status)
    """)
    Page<Ticket> findAllByUserIdWithCondition(
            @Param("userId") Long userId,
            @Param("name") String name,
            @Param("type") TicketType type,
            @Param("status") TicketStatus status,
            Pageable stablePageable
    );

    @Query("SELECT t FROM Ticket t WHERE t.code = :code")
    Optional<Ticket> findByCode(@Param("code") String code);

    // 내 티켓 상세 조회
    @Query("""
    SELECT t
    FROM Ticket t
    WHERE t.id = :ticketId AND t.orderItem.order.user.id = :userId
    """)
    Optional<Ticket> findByIdAndUser_Id(@Param("ticketId") Long ticketId, @Param("userId") Long userId);
}
