package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.facade.PaymentFacade;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.global.response.PageableDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    public final PaymentService paymentService;
    private final PaymentFacade paymentFacade;

    @GetMapping
    public ResponseEntity<ApiResponse<PageableDto<PaymentResponse>>> getPaymentAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @PageableDefault Pageable pageable) {
        SearchPaymentCondition condition = new SearchPaymentCondition(paymentStatus);

        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.findAllPaymentsByUserId(userId, condition, pageable))
        );
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentDetailResponse>> getPaymentById(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long paymentId) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.findPaymentById(userId, paymentId))
        );
    }

    @PostMapping("/{pgId}/confirm")
    public ResponseEntity<ApiResponse<ConfirmPaymentResponse>> confirmPayment(
            @AuthenticationPrincipal Long userId,
            @PathVariable String pgId,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentFacade.confirmPayment(userId, pgId, request)));
    }
}
