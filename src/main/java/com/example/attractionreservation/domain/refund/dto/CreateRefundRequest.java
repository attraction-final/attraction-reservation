package com.example.attractionreservation.domain.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 전체 환불 요청
public record CreateRefundRequest(
        @NotBlank
        @Size(max = 200)
        String reason
) {
}
