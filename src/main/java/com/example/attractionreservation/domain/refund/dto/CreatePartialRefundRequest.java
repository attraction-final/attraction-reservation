package com.example.attractionreservation.domain.refund.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// 부분 환불 요청. 환불할 티켓을 ID로 지정한다.
public record CreatePartialRefundRequest(
        @NotEmpty
        List<@NotNull Long> ticketIds,

        @NotBlank
        @Size(max = 200)
        String reason
) {
}
