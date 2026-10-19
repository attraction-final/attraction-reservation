package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    // 티켓 상품 수정
    @PatchMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<UpdateTicketProductResponse>> update(
            @Valid @RequestBody UpdateTicketProductRequest request,
            @PathVariable Long ticketProductId
    ) {
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품이 수정되었습니다.", ticketProductService.update(ticketProductId, request)));
    }
}
