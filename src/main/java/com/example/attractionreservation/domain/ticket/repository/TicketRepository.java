package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketCustomRepository {

    @Query("SELECT t FROM Ticket t WHERE t.code = :code")
    Optional<Ticket> findByCode(@Param("code") String code);

    // 오늘 또는 미래에 사용할 수 있는 미사용 입장권을 확인한다.
    boolean existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual(
            Long userId,
            TicketStatus status,
            LocalDate today
    );

    // 환불할 티켓을 잠가서 조회한다. 주문 상품 ID로 다른 주문의 티켓을 걸러낸다. 교착을 피하려고 ID 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.id IN :ticketIds AND t.orderItem.id IN :orderItemIds ORDER BY t.id")
    List<Ticket> findAllByIdInAndOrderItemIdInForUpdate(
            @Param("ticketIds") Collection<Long> ticketIds,
            @Param("orderItemIds") Collection<Long> orderItemIds
    );

    // 주문 상품들의 특정 상태 티켓을 잠가서 조회한다. 교착을 피하려고 ID 순서로 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.orderItem.id IN :orderItemIds AND t.status = :status ORDER BY t.id")
    List<Ticket> findAllByOrderItemIdInAndStatusForUpdate(
            @Param("orderItemIds") Collection<Long> orderItemIds,
            @Param("status") TicketStatus status
    );
}