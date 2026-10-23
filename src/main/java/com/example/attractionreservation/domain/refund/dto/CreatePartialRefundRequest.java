package com.example.attractionreservation.domain.refund.dto;

import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 부분 환불 요청
public record CreatePartialRefundRequest(
        @NotEmpty
        List<@Valid RefundItemRequest> items,

        @NotBlank
        @Size(max = 200)
        String reason
) {
    // 주문 상품 ID별 환불 수량. 같은 주문 상품이 두 번 들어오면 거절한다.
    public Map<Long, Integer> toRefundQuantities() {
        return items.stream()
                .collect(Collectors.toMap(
                        RefundItemRequest::orderItemId,
                        RefundItemRequest::quantity,
                        (first, second) -> {
                            throw new BusinessException(ErrorCode.REFUND_DUPLICATE_ORDER_ITEM);
                        }));
    }
}
