package com.example.attractionreservation.domain.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTicketEntryRequest(
        @NotBlank(message = "티켓 코드를 입력해주세요.")
        String ticketCode
) {
}
