package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import jakarta.validation.constraints.NotBlank;
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
    Page<Ticket> findAllByUserIdWithCondition(Long userId, String name, TicketType type, TicketStatus status, Pageable stablePageable);

    @Query("SELECT t FROM Ticket t WHERE t.code = :code")
    Optional<Ticket> findByCode(@Param("code") String code);

    // 티켓 → 주문 항목 → 주문 → 회원을 따라 본인의 미사용 입장권을 확인한다.
    boolean existsByOrderItem_Order_User_IdAndStatus(
            Long userId,
            TicketStatus status
    );
}
