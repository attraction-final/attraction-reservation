package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Query("""
    SELECT t FROM Ticket t
    WHERE t.orderItem.order.user.id = :userId AND (:name IS NULL OR t.name LIKE CONCAT('%', :name, '%'))
        AND (:type IS NULL OR t.type = :type)
        AND (:status IS NULL OR t.status = :status)
    """)
    Page<Ticket> findAllByUserIdWithCondition(Long userId, String name, TicketType type, TicketStatus status, Pageable stablePageable);
}
