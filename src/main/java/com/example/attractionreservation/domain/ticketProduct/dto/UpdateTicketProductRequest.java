package com.example.attractionreservation.domain.ticketProduct.dto;

import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTicketProductRequest(

        @NotBlank(message = "상품명을 입력해 주세요.")
        @Size(max = 80, message = "상품명은 80자를 넘을 수 없습니다.")
        String name,

        @NotNull(message = "가격을 입력해주세요.")
        @Min(value = 0, message = "가격은 0이상 입력해야 합니다.")
        Integer price,

        @NotBlank(message = "설명을 입력해주세요.")
        @Size(max = 200, message = "설명은 200자를 넘을 수 없습니다.")
        String description,

        @NotNull(message = "티켓 판매 상태를 입력해주세요.")
        TicketProductStatus saleStatus,

        @NotNull(message = "재고를 입력해주세요.")
        @Min(value = 0, message = "재고는 0이상 입력해야 합니다.")
        Integer stock
) {
}
