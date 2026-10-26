package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
}