package com.example.attractionreservation.domain.reservation.controller;

import com.example.attractionreservation.domain.reservation.dto.*;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reservations")
@Slf4j
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

    // 관리자 예약 상세 조회
    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<SearchAdminReservationDetailResponse>> getAdminReservationById(
            @Min(1) @PathVariable Long reservationId
    ) {
        SearchAdminReservationDetailResponse response = reservationService.findAdminReservationById(reservationId);
        return ResponseEntity.ok(ApiResponse.ok("예약 상세 조회에 성공했습니다.", response));
    }

    // 관리자 예약 강제 취소
    @PatchMapping("/{reservationId}/cancel")
    public ResponseEntity<ApiResponse<UpdateAdminReservationCancelResponse>> adminReservationCancel(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @Min(1) @PathVariable Long reservationId,
            @Valid @RequestBody UpdateAdminReservationCancelRequest request
    ) {
        UpdateAdminReservationCancelResponse response = reservationService.adminReservationCancel(reservationId, request);
        log.info("event=RESERVATION_ADMIN_CANCELLED reservationId={} userId={} reason=\"{}\"",
                response.reservationId(),
                userId,
                response.cancelReason()
        );
        return ResponseEntity.ok(ApiResponse.ok("예약 강제 취소에 성공했습니다.", response));
    }

    // 관리자 예약 상태 변경
    @PatchMapping("/{reservationId}/status")
    public ResponseEntity<ApiResponse<UpdateAdminReservationStatusResponse>> updateReservationStatus(
            @AuthenticationPrincipal Long userId, // 로깅용 관리자 id
            @Min(1) @PathVariable Long reservationId,
            @Valid @RequestBody UpdateAdminReservationStatusRequest request
    ) {
        UpdateAdminReservationStatusResponse response = reservationService.updateReservationStatus(reservationId, request);
        switch (response.status()) {
            case COMPLETED -> log.info("event=RESERVATION_COMPLETED reservationId={} userId={}",
                    response.reservationId(), userId);
            case NO_SHOW -> log.info("event=RESERVATION_NO_SHOW_RECORDED reservationId={} userId={}",
                    response.reservationId(), userId);
            default -> {}  // 현재 상태 변경 API의 성공 대상은 COMPLETED와 NO_SHOW다.
        }
        return ResponseEntity.ok(ApiResponse.ok("예약 상태 변경에 성공했습니다.", response));
    }
}
