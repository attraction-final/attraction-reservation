package com.example.attractionreservation.domain.ticketProduct.repository;

import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface TicketProductCustomRepository {

    Page<TicketProduct> findAllByCondition(
            String name,
            TicketType type,
            TicketProductStatus status,
            Pageable pageable
    );
}
