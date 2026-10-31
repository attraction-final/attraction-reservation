package com.example.attractionreservation.domain.ticket.controller;

import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryRequest;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryResponse;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/tickets")
@Slf4j
public class AdminTicketController {

    private final TicketService ticketService;

    // 티켓 입장 처리
    @PostMapping("/entry")
    public ResponseEntity<ApiResponse<CreateTicketEntryResponse>> createTicketEntry(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @Valid @RequestBody CreateTicketEntryRequest request
    ) {
        CreateTicketEntryResponse response = ticketService.createTicketEntry(request);
        log.info("event=TICKET_ENTERED ticketId={} userId={}",
                response.ticketId(), userId
        );
        return ResponseEntity.ok(ApiResponse.ok("티켓 입장 처리에 성공했습니다.", response));
    }
}