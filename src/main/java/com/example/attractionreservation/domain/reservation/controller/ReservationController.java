package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.*;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    // 예약 생성
    @PostMapping
    public ResponseEntity<ApiResponse<CreateReservationResponse>> createReservation(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        CreateReservationResponse response = reservationService.createReservation(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("어트랙션 예약에 성공했습니다.", response));
    }

    // 내 예약 목록 조회
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SearchReservationListResponse>> getReservationAll(
            @AuthenticationPrincipal Long userId,
            @ModelAttribute SearchReservationCondition condition,
            @PageableDefault(page = 0, size = 10, sort = "status", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Sort sort = pageable.getSort();

        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Pageable stablePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<SearchReservationResponse> result = reservationService.findReservationAll(userId, stablePageable, condition);

        SearchReservationListResponse response = SearchReservationListResponse.from(result);

        return ResponseEntity.ok(ApiResponse.ok("내 예약 목록 조회에 성공했습니다.", response));
    }

    // 내 예약 상세 조회
    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<SearchReservationDetailResponse>> getReservationById(
            @AuthenticationPrincipal Long userId,
            @Min(1) @PathVariable Long reservationId
    ) {
        SearchReservationDetailResponse response = reservationService.findReservationById(userId, reservationId);
        return ResponseEntity.ok(ApiResponse.ok("예약 상세 조회에 성공했습니다.", response));
    }

    // 내 예약 취소
    @PatchMapping("/{reservationId}/cancel")
    public ResponseEntity<ApiResponse<UpdateReservationCancelResponse>> cancelReservation(
            @AuthenticationPrincipal Long userId,
            @Min(1) @PathVariable Long reservationId
    ) {
        UpdateReservationCancelResponse response = reservationService.cancelReservation(userId, reservationId);
        return ResponseEntity.ok(ApiResponse.ok("예약 취소에 성공했습니다.", response));
    }
}
