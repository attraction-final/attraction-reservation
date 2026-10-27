package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long>, TicketProductCustomRepository {
}
