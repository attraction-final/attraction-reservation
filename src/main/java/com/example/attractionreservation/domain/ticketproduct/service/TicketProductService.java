package com.example.attractionreservation.domain.ticketproduct.service;

import com.example.attractionreservation.domain.ticketproduct.repository.TicketProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketProductService {

    private final TicketProductRepository ticketProductRepository;
}
