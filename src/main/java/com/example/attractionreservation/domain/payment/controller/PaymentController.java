package com.example.attractionreservation.domain.payment.controller;

import com.example.attractionreservation.domain.payment.dto.PaymentDetailResponse;
import com.example.attractionreservation.domain.payment.dto.PaymentResponse;
import com.example.attractionreservation.domain.payment.dto.SearchPaymentCondition;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.global.response.PageableDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    public final PaymentService paymentService;


    //todo: 인증 추가 시 userId를 authenticationPrincipal에서 가져와야 함
    @GetMapping
    public ResponseEntity<ApiResponse<PageableDto<PaymentResponse>>> getPaymentAll(
            @RequestParam Long userId,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @PageableDefault Pageable pageable) {
        SearchPaymentCondition condition = new SearchPaymentCondition(paymentStatus);

        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.findAllPaymentsByUserId(userId, condition, pageable))
        );
    }

    //todo: 인증 추가 시 userId를 authenticationPrincipal에서 가져와야 함
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentDetailResponse>> getPaymentById(
            @PathVariable Long paymentId,
            @RequestParam Long userId) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.findPaymentById(userId, paymentId))
        );
    }
}
