package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.CreateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductRequest;
import com.example.attractionreservation.domain.ticketProduct.dto.UpdateTicketProductResponse;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/ticket-products")
@Slf4j
public class AdminTicketProductController {

    private final TicketProductService ticketProductService;

    // 티켓 상품 등록
    @PostMapping
    public ResponseEntity<ApiResponse<CreateTicketProductResponse>> createTicketProduct(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @Valid @RequestBody CreateTicketProductRequest request
    ) {
        CreateTicketProductResponse response = ticketProductService.createTicketProduct(request);
        log.info("event=TICKET_PRODUCT_CREATED ticketProductId={} userId={}",
                response.id(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("티켓 상품이 등록되었습니다.", response));
    }

    // 티켓 상품 수정
    @PutMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<UpdateTicketProductResponse>> updateTicketProduct(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @Valid @RequestBody UpdateTicketProductRequest request,
            @PathVariable Long ticketProductId
    ) {
        UpdateTicketProductResponse response = ticketProductService.updateTicketProduct(ticketProductId, request);
        log.info("event=TICKET_PRODUCT_UPDATED ticketProductId={} userId={}",
                response.id(), userId);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품이 수정되었습니다.", response));
    }

    // 티켓 상품 삭제
    @DeleteMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<Void>> deleteTicketProduct(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @PathVariable Long ticketProductId
    ) {
        ticketProductService.deleteTicketProduct(ticketProductId);
        log.info("event=TICKET_PRODUCT_DELETED ticketProductId={} userId={}",
                ticketProductId, userId);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품이 삭제되었습니다.", null));
    }
}
