package com.example.attractionreservation.domain.refund.controller;

import com.example.attractionreservation.domain.refund.dto.CreatePartialRefundRequest;
import com.example.attractionreservation.domain.refund.dto.CreateRefundRequest;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.facade.RefundFacade;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class RefundController {
    private final RefundFacade refundFacade;

    // 전체 환불
    @PostMapping("/{pgId}/refunds")
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @AuthenticationPrincipal Long userId,
            @PathVariable String pgId,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        RefundResponse response = refundFacade.createRefund(userId, pgId, request);
        return ResponseEntity.ok(ApiResponse.ok("환불 요청에 성공했습니다.", response));
    }

    // 부분 환불
    @PostMapping("/{pgId}/refunds/partial")
    public ResponseEntity<ApiResponse<RefundResponse>> createPartialRefund(
            @AuthenticationPrincipal Long userId,
            @PathVariable String pgId,
            @Valid @RequestBody CreatePartialRefundRequest request
    ) {
        RefundResponse response = refundFacade.createPartialRefund(userId, pgId, request);
        return ResponseEntity.ok(ApiResponse.ok("부분 환불 요청에 성공했습니다.", response));
    }
}
