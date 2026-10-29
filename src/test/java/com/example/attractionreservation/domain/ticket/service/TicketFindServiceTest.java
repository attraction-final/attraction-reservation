package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketFindServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private TicketService ticketService;

    @Test
    @DisplayName("존재하는 티켓 ID로 조회하면 해당 티켓을 반환한다")
    void findByTicketId_success() {
        // given
        Long ticketId = 10L;
        Ticket ticket = mock(Ticket.class);

        when(ticketRepository.findById(ticketId))
                .thenReturn(Optional.of(ticket));

        // when
        Ticket result = ticketService.findByTicketId(ticketId);

        // then
        assertThat(result).isSameAs(ticket);

        verify(ticketRepository).findById(ticketId);
    }

    @Test
    @DisplayName("존재하지 않는 티켓 ID로 조회하면 예외가 발생한다")
    void findByTicketId_notFound() {
        // given
        Long ticketId = 999L;

        when(ticketRepository.findById(ticketId))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> ticketService.findByTicketId(ticketId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_NOT_FOUND)
                );

        verify(ticketRepository).findById(ticketId);
    }

    @Test
    @DisplayName("환불할 티켓이 모두 해당 주문 상품의 티켓이면 잠가서 조회한 티켓을 반환한다")
    void findRefundTicketsForUpdate_allFound_ReturnTickets() {
        // given
        Set<Long> orderItemIds = Set.of(100L);
        Set<Long> ticketIds = Set.of(1L, 2L);
        List<Ticket> tickets = List.of(mock(Ticket.class), mock(Ticket.class));

        when(ticketRepository.findAllByIdInAndOrderItemIdInForUpdate(ticketIds, orderItemIds))
                .thenReturn(tickets);

        // when
        List<Ticket> result = ticketService.findRefundTicketsForUpdate(orderItemIds, ticketIds);

        // then
        assertThat(result).isSameAs(tickets);
    }

    @Test
    @DisplayName("환불할 티켓 중 없거나 해당 주문 상품의 티켓이 아닌 것이 있으면 예외가 발생한다")
    void findRefundTicketsForUpdate_someNotFound_Fail() {
        // given
        Set<Long> orderItemIds = Set.of(100L);
        Set<Long> ticketIds = Set.of(1L, 999L);

        when(ticketRepository.findAllByIdInAndOrderItemIdInForUpdate(ticketIds, orderItemIds))
                .thenReturn(List.of(mock(Ticket.class)));

        // when & then
        assertThatThrownBy(() -> ticketService.findRefundTicketsForUpdate(orderItemIds, ticketIds))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.REFUND_INVALID_TICKET)
                );
    }

    @Test
    @DisplayName("환불용 미사용 티켓 조회는 UNUSED 상태로 잠가서 조회한다")
    void findUnusedTicketsForUpdate_queryUnused() {
        // given
        Set<Long> orderItemIds = Set.of(100L, 200L);
        List<Ticket> tickets = List.of(mock(Ticket.class));

        when(ticketRepository.findAllByOrderItemIdInAndStatusForUpdate(orderItemIds, TicketStatus.UNUSED))
                .thenReturn(tickets);

        // when
        List<Ticket> result = ticketService.findUnusedTicketsForUpdate(orderItemIds);

        // then
        assertThat(result).isSameAs(tickets);
    }
}
