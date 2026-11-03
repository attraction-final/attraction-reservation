package com.example.attractionreservation.domain.ticket.event;

/**
 * 티켓 발급 로그에 필요한 주문 ID, 소유자 ID, 발급 수량을 전달하는 event.
 * 엔티티 대신 값만 담으며, commit 이후 처리는 Listener에서 설정한다.
 *
 * @param orderId 티켓 발급 대상 주문 ID
 * @param userId 티켓 소유자 ID
 * @param count 이번에 발급한 티켓 수
 */
public record TicketIssuedEvent(
        Long orderId,
        Long userId,
        int count
) {
}
