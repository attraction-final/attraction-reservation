package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationCondition;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationListResponse;
import com.example.attractionreservation.domain.reservation.dto.SearchAdminReservationResponse;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reservations")
public class AdminReservationController {

    private final ReservationService reservationService;

    // 관리자 예약 목록 조회
    @GetMapping
    public ResponseEntity<ApiResponse<SearchAdminReservationListResponse>> getAdminReservationAll(
            @Valid @ModelAttribute SearchAdminReservationCondition condition,
            @PageableDefault(sort = "status", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Sort sort = pageable.getSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Pageable stablePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<SearchAdminReservationResponse> result = reservationService.findAdminReservationAll(condition, stablePageable);
        SearchAdminReservationListResponse response = SearchAdminReservationListResponse.from(result);

        return ResponseEntity.ok(ApiResponse.ok("예약 목록 조회에 성공했습니다.", response));
    }
}
