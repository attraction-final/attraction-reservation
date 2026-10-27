package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface TicketCustomRepository {

    Page<Ticket> findAllByUserIdWithCondition(
            Long userId,
            String name,
            TicketType type,
            TicketStatus status,
            Pageable stablePageable
    );

    Optional<Ticket> findByIdAndOwner_Id(
            Long ticketId,
            Long userId);
}
