package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryRequest;
import com.example.attractionreservation.domain.ticket.dto.CreateTicketEntryResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketEntryServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private TicketService ticketService;

    @Test
    void 코드로_조회한_티켓에_입장을_위임하고_응답을_반환한다() {
        String code = "TKT-test";
        Ticket ticket = mock(Ticket.class);
        Instant enteredAt = Instant.parse("2026-10-22T01:00:00Z");

        when(ticketRepository.findByCode(code))
                .thenReturn(Optional.of(ticket));
        when(ticket.getId()).thenReturn(1L);
        when(ticket.getCode()).thenReturn(code);
        when(ticket.getStatus()).thenReturn(TicketStatus.IN_USE);
        when(ticket.getEnteredAt()).thenReturn(enteredAt);

        Instant before = Instant.now();

        CreateTicketEntryResponse response = ticketService.createTicketEntry(
                new CreateTicketEntryRequest(code)
        );

        Instant after = Instant.now();

        ArgumentCaptor<Instant> captor =
                ArgumentCaptor.forClass(Instant.class);
        verify(ticket).entryTicket(captor.capture());

        assertThat(captor.getValue()).isBetween(before, after);
        assertThat(response.ticketId()).isEqualTo(1L);
        assertThat(response.ticketCode()).isEqualTo(code);
        assertThat(response.status()).isEqualTo(TicketStatus.IN_USE);
        assertThat(response.enteredAt()).isEqualTo(enteredAt);
    }

    @Test
    void 존재하지_않는_티켓이면_예외가_발생한다() {
        when(ticketRepository.findByCode("unknown"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.createTicketEntry(
                new CreateTicketEntryRequest("unknown")
        ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_NOT_FOUND)
                );
    }

    @Test
    void 엔티티의_입장거절_예외를_그대로_전달한다() {
        Ticket ticket = mock(Ticket.class);
        when(ticketRepository.findByCode("TKT-test"))
                .thenReturn(Optional.of(ticket));

        doThrow(new BusinessException(
                ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS
        )).when(ticket).entryTicket(any(Instant.class));

        assertThatThrownBy(() -> ticketService.createTicketEntry(
                new CreateTicketEntryRequest("TKT-test")
        ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS
                                )
                );
    }
}