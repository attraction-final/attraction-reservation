package com.example.attractionreservation.domain.order.controller;

import com.example.attractionreservation.domain.order.dto.SearchAdminOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderResponse;
import com.example.attractionreservation.domain.order.service.AdminOrderService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.example.attractionreservation.domain.order.dto.CreateAdminOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    @GetMapping
    public ResponseEntity<ApiResponse<SearchAdminOrderListResponse>> getOrders(
            @Valid @ModelAttribute SearchAdminOrderCondition condition
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        "관리자 주문 목록 조회에 성공했습니다.",
                        adminOrderService.findOrders(condition)
                )
        );
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<SearchAdminOrderResponse>> getOrderById(
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(
                ApiResponse.ok(
                        "관리자 주문 상세 조회에 성공했습니다.",
                        adminOrderService.findOrderById(orderId)
                )
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @AuthenticationPrincipal Long adminId,
            @Valid @RequestBody CreateAdminOrderRequest request
    ) {
        CreateOrderResponse response =
                adminOrderService.createOrder(adminId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        "관리자 주문 생성에 성공했습니다.",
                        response
                ));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<UpdateOrderResponse>> cancelOrder(
            @AuthenticationPrincipal Long adminId,
            @PathVariable Long orderId
    ) {
        UpdateOrderResponse response =
                adminOrderService.cancelOrder(adminId, orderId);

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "관리자 주문 취소에 성공했습니다.",
                        response
                )
        );
    }
}