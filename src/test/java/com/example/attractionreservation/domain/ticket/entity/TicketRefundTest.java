package com.example.attractionreservation.domain.ticket.entity;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketRefundTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 11, 4);
    private static final Instant ENTRY_TIME = VISIT_DATE.atTime(16, 0).atZone(KstTime.ZONE).toInstant();
    private static final Instant CLOSE_TIME = VISIT_DATE.atTime(22, 0).atZone(KstTime.ZONE).toInstant();

    @ParameterizedTest
    @EnumSource(TicketType.class)
    @DisplayName("미사용 티켓은 환불 상태로 전환하고 입장·종료 시각은 변경하지 않는다")
    void refund_UnusedTicket_ChangesStatusAndPreservesTimes(TicketType type) {
        // given
        Ticket ticket = createTicket(type);
        Instant enteredAt = ticket.getEnteredAt();
        Instant endTime = ticket.getEndTime();

        // when
        ticket.refund();

        // then
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.REFUNDED);
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
        assertThat(ticket.getEndTime()).isEqualTo(endTime);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_USE", "USED", "EXPIRED", "REFUNDED"})
    @DisplayName("미사용이 아닌 티켓의 환불은 거부하고 기존 상태와 입장·종료 시각을 유지한다")
    void refund_NotUnusedTicket_RejectsAndPreservesValues(TicketStatus status) {
        // given
        Ticket ticket = createTicketInStatus(status);
        Instant enteredAt = ticket.getEnteredAt();
        Instant endTime = ticket.getEndTime();

        // when & then
        assertThatThrownBy(ticket::refund)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TICKET_NOT_REFUNDABLE));
        assertThat(ticket.getStatus()).isEqualTo(status);
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
        assertThat(ticket.getEndTime()).isEqualTo(endTime);
    }

    private Ticket createTicketInStatus(TicketStatus status) {
        Ticket ticket = createTicket(TicketType.ALL_DAY);
        // 공개된 업무 메서드로 상태와 관련 시각을 함께 준비한다.
        switch (status) {
            case IN_USE -> ticket.entryTicket(ENTRY_TIME);
            case USED -> {
                ticket.entryTicket(ENTRY_TIME);
                ticket.expireIfClosed(CLOSE_TIME);
            }
            case EXPIRED -> ticket.expireIfClosed(CLOSE_TIME);
            case REFUNDED -> ticket.refund();
            case UNUSED -> {
                // 생성 직후 상태를 유지한다.
            }
        }
        return ticket;
    }

    private Ticket createTicket(TicketType type) {
        TicketProduct product = mock(TicketProduct.class);
        when(product.getName()).thenReturn("테스트 입장권");
        when(product.getType()).thenReturn(type);
        OrderItem item = mock(OrderItem.class);
        when(item.getTicketProduct()).thenReturn(product);
        return new Ticket(VISIT_DATE, item);
    }
}
