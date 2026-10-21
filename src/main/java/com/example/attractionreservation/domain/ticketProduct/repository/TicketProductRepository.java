package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketProductRepository extends JpaRepository<TicketProduct, Long> {

    // 티켓 상품 목록 조회
    @Query("""
    SELECT tp FROM TicketProduct tp
    WHERE (:name IS NULL OR tp.name LIKE CONCAT('%', :name, '%'))
        AND (:type IS NULL OR tp.type = :type) 
        AND (:status IS NULL OR tp.status = :status)
        AND tp.status <> com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus.DELETED
    """)
    Page<TicketProduct> findAllByCondition(@Param("name") String name, @Param("type") TicketType type, @Param("status") TicketProductStatus status, Pageable pageable);
}
