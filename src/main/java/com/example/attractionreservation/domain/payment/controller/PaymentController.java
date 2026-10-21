package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentRequest;
import com.example.attractionreservation.domain.payment.dto.ConfirmPaymentResponse;
import com.example.attractionreservation.domain.payment.facade.PaymentFacade;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentFacade paymentFacade;

    // TODO: 인증 추가 시 userId @AuthenticationPrincipal로 변경해야 함
    @PostMapping("/{pgId}/confirm")
    public ResponseEntity<ApiResponse<ConfirmPaymentResponse>> confirmPayment(
            @RequestParam("userId") Long userId,
            @PathVariable String pgId,
            @Valid @RequestBody ConfirmPaymentRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(paymentFacade.confirmPayment(userId, pgId, request)));
    }

}
