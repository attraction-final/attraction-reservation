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

    // TODO: 관리자 인증 구현 후 인증 검사 추가

    // 티켓 상품 등록
    @PostMapping
    public ResponseEntity<ApiResponse<CreateTicketProductResponse>> createTicketProduct(
            @Valid @RequestBody CreateTicketProductRequest request
    ) {
        CreateTicketProductResponse response = ticketProductService.createTicketProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("티켓 상품이 등록되었습니다.", response));
    }

    // 티켓 상품 수정
    @PutMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<UpdateTicketProductResponse>> updateTicketProduct(
            @Valid @RequestBody UpdateTicketProductRequest request,
            @PathVariable Long ticketProductId
    ) {
        UpdateTicketProductResponse response = ticketProductService.updateTicketProduct(ticketProductId, request);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품이 수정되었습니다.", response));
    }

    // 티켓 상품 삭제
    @DeleteMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<Void>> deleteTicketProduct(
            @PathVariable Long ticketProductId
    ) {
        ticketProductService.deleteTicketProduct(ticketProductId);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품이 삭제되었습니다.", null));
    }
}
