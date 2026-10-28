package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Collection;
import java.util.List;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long>, TicketProductCustomRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TicketProduct> findAllWithUpdateByIdInOrderById(Collection<Long> ids);
}
