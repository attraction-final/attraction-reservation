package com.example.attractionreservation.domain.refund.dto;

// 주문 상품별 환불 수량 합계. 환불 가능 수량 계산에 사용한다.
public record RefundedQuantity(
        Long orderItemId,
        Long quantity
) {
}
