package com.example.attractionreservation.domain.refund.controller;

import com.example.attractionreservation.domain.refund.dto.CreatePartialRefundRequest;
import com.example.attractionreservation.domain.refund.dto.CreateRefundRequest;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.facade.RefundFacade;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
@Slf4j
public class AdminRefundController {

    private final RefundFacade refundFacade;

    // 관리자 전체 환불
    @PostMapping("/{pgId}/refunds")
    public ResponseEntity<ApiResponse<RefundResponse>> createAdminRefund(
            @AuthenticationPrincipal Long userId, // 관리자 id
            @PathVariable String pgId,
            @Valid @RequestBody CreateRefundRequest request
    ) {
        RefundResponse response = refundFacade.createAdminFullRefund(pgId, request);
        log.info("event=REFUND_ADMIN_FULL_REQUESTED refundId={} userId={} pgId={} amount={} status={}",
                response.refundId(),
                userId,
                pgId,
                response.refundAmount(),
                response.status()
                );
        return ResponseEntity.ok(ApiResponse.ok("환불 요청에 성공했습니다.", response));
    }

    // 관리자 부분 환불
    @PostMapping("/{pgId}/refunds/partial")
    public ResponseEntity<ApiResponse<RefundResponse>> createAdminPartialRefund(
            @AuthenticationPrincipal Long userId, // 관리자 id
            @PathVariable String pgId,
            @Valid @RequestBody CreatePartialRefundRequest request
    ) {
        RefundResponse response = refundFacade.createAdminPartialRefund(pgId, request);
        log.info("event=REFUND_ADMIN_PARTIAL_REQUESTED refundId={} userId={} pgId={} amount={} status={}",
                response.refundId(),
                userId,
                pgId,
                response.refundAmount(),
                response.status()
                );
        return ResponseEntity.ok(ApiResponse.ok("부분 환불 요청에 성공했습니다.", response));
    }
}
