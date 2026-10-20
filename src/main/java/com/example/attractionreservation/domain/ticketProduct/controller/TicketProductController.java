package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.*;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

    // 티켓 상품 목록 조회 (이름 + 상태 + 종류 + 페이징)
    @GetMapping
    public ResponseEntity<ApiResponse<Page<SearchTicketProductResponse>>> getTicketProductAll(
            @ModelAttribute SearchTicketProductCondition condition,
            @PageableDefault(page = 0, size = 10, sort = "type", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<SearchTicketProductResponse> response = ticketProductService.getTicketProductAll(condition, pageable);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품 목록 조회에 성공했습니다.", response));
    }
}
