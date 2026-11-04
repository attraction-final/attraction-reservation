package com.example.attractionreservation.domain.ticket.dto;

// 내 티켓 목록 응답에 붙일 주문번호와 결제번호. 티켓 ID 묶음으로 한 번에 조회할 때 쓴다.
// 결제가 아직 만들어지지 않은 주문이면 pgId는 null이다.
public record TicketOrderPayment(
        Long ticketId,
        Long orderId,
        String pgId
) {
}