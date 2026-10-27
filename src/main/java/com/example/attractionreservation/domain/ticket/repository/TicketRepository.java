package com.example.attractionreservation.domain.ticket.repository;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long>, TicketCustomRepository {

    @Query("SELECT t FROM Ticket t WHERE t.code = :code")
    Optional<Ticket> findByCode(@Param("code") String code);
}
