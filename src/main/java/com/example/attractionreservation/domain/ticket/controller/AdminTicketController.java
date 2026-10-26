package com.example.attractionreservation.domain.ticket.controller;

import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryRequest;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryResponse;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/tickets")
public class AdminTicketController {

    private final TicketService ticketService;

    // 티켓 입장 처리
    @PostMapping("/entry")
    public ResponseEntity<ApiResponse<CreateTicketEntryResponse>> createTicketEntry(
            @Valid @RequestBody CreateTicketEntryRequest request
    ) {
        CreateTicketEntryResponse response = ticketService.createTicketEntry(request);
        return ResponseEntity.ok(ApiResponse.ok("티켓 입장 처리에 성공했습니다.", response));
    }
}