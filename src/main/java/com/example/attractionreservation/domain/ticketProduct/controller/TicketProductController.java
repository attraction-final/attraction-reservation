package com.example.attractionreservation.domain.ticketProduct.controller;

import com.example.attractionreservation.domain.ticketProduct.dto.*;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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

    // 티켓 상품 목록 조회 (이름 + 상태 + 종류 + 페이징)
    @GetMapping
    public ResponseEntity<ApiResponse<SearchTicketProductListResponse>> getTicketProductAll(
            @ModelAttribute SearchTicketProductCondition condition,
            @PageableDefault(page = 0, size = 10, sort = "type", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Sort sort = pageable.getSort();

        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }

        Pageable stablePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        Page<SearchTicketProductResponse> result = ticketProductService.getTicketProductAll(condition, stablePageable);

        SearchTicketProductListResponse response = SearchTicketProductListResponse.from(result);

        return ResponseEntity.ok(ApiResponse.ok("티켓 상품 목록 조회에 성공했습니다.", response));
    }

    // 티켓 상품 상세 조회
    @GetMapping("/{ticketProductId}")
    public ResponseEntity<ApiResponse<SearchTicketProductResponse>> getTicketProductById(
            @PathVariable Long ticketProductId
    ) {
        SearchTicketProductResponse response = ticketProductService.findTicketProductById(ticketProductId);
        return ResponseEntity.ok(ApiResponse.ok("티켓 상품 상세 조회에 성공했습니다.", response));
    }
}
