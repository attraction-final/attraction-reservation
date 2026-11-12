package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long>, TicketProductCustomRepository {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TicketProduct> findAllWithUpdateByIdInOrderById(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT tp FROM TicketProduct tp WHERE tp.id = :ticketProductId")
    Optional<TicketProduct> findByIdWithLock(@Param("ticketProductId") Long ticketProductId);
}
