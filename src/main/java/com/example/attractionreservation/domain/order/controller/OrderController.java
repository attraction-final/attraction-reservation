package com.example.attractionreservation.domain.order.controller;

import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> createOrder(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        // 주문자는 요청값이 아닌 인증된 회원 ID로 식별한다.
        CreateOrderResponse response = orderService.createOrder(userId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("주문 생성에 성공했습니다.", response));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<SearchOrderResponse>> getOrderById(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId
    ) {
        // 조회 대상은 URL에서 받고, 접근 권한은 인증된 회원 ID로 검사한다.
        SearchOrderResponse response =
                orderService.findOrderById(userId, orderId);

        return ResponseEntity.ok(
                ApiResponse.ok("주문 상세 조회에 성공했습니다.", response)
        );
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<ApiResponse<UpdateOrderResponse>> updateOrderByCancel(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long orderId
    ) {
        // 요청값으로 회원 ID를 받지 않고 인증된 회원의 주문만 취소하도록 한다.
        UpdateOrderResponse response =
                orderService.updateOrderByCancel(userId, orderId);

        return ResponseEntity.ok(
                ApiResponse.ok("주문 취소에 성공했습니다.", response)
        );
    }
}