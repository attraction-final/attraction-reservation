package com.example.attractionreservation.domain.ticketproduct.controller;

import com.example.attractionreservation.domain.ticketproduct.service.TicketProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TicketProductController {

    private final TicketProductService ticketProductService;
}
