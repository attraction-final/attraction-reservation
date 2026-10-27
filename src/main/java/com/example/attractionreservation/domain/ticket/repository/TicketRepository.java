package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}