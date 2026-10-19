package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ticket-products")
public class TicketProductController {

    private final TicketProductService ticketProductService;

    // 티켓 상품 등록
    @PostMapping
    public ResponseEntity<ApiResponse<CreateTicketProductResponse>> create(
            @Valid @RequestBody CreateTicketProductRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("티켓 상품이 등록되었습니다.", ticketProductService.create(request)));
    }
}
