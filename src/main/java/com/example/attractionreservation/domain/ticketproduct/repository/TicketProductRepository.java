package com.example.attractionreservation.domain.ticketproduct.repository;

import com.example.attractionreservation.domain.ticketproduct.entity.TicketProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long> {
}
