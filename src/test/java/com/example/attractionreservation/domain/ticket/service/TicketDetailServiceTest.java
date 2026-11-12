package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketDetailResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketDetailServiceTest {

    private static final Long TICKET_ID = 1L;
    private static final Long PRODUCT_ID = 10L;
    private static final LocalDate VALID_DATE =
            LocalDate.of(2026, 10, 25);
    private static final Long USER_ID = 100L;

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private TicketService ticketService;

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    @DisplayName("티켓 상태와 관계없이 상세 정보를 조회한다")
    void findTicketById_ExistingTicket_ReturnsDetail(TicketStatus status) {
        // given
        Ticket ticket = createTicket(status, null);

        when(ticketRepository.findByIdAndOwner_Id(TICKET_ID, USER_ID))
                .thenReturn(Optional.of(ticket));

        // when
        SearchTicketDetailResponse response =
                ticketService.findTicketById(USER_ID, TICKET_ID);

        // then
        assertThat(response).isEqualTo(
                new SearchTicketDetailResponse(
                        TICKET_ID,
                        "TKT-test-code",
                        PRODUCT_ID,
                        "발급 당시 종일이용권",
                        TicketType.ALL_DAY,
                        VALID_DATE,
                        status,
                        null
                )
        );

        verify(ticketRepository).findByIdAndOwner_Id(TICKET_ID, USER_ID);
    }

    @Test
    @DisplayName("입장 시각이 있는 티켓은 해당 시각을 반환한다")
    void findTicketById_EnteredTicket_ReturnsEnteredAt() {
        // given
        Instant enteredAt = Instant.parse("2026-10-25T01:00:00Z");
        Ticket ticket = createTicket(TicketStatus.IN_USE, enteredAt);

        when(ticketRepository.findByIdAndOwner_Id(TICKET_ID, USER_ID))
                .thenReturn(Optional.of(ticket));

        // when
        SearchTicketDetailResponse response =
                ticketService.findTicketById(USER_ID, TICKET_ID);

        // then
        assertThat(response.enteredAt()).isEqualTo(enteredAt);
        assertThat(response.status()).isEqualTo(TicketStatus.IN_USE);

        verify(ticketRepository).findByIdAndOwner_Id(TICKET_ID, USER_ID);
    }

    @Test
    @DisplayName("조회 가능한 본인 티켓이 없으면 TICKET_NOT_FOUND 예외를 발생시킨다")
    void findTicketById_NotFound_ThrowsException() {
        // given
        Long ticketId = 999L;

        when(ticketRepository.findByIdAndOwner_Id(ticketId, USER_ID))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> ticketService.findTicketById(USER_ID, ticketId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.TICKET_NOT_FOUND)
                );

        verify(ticketRepository).findByIdAndOwner_Id(ticketId, USER_ID);
    }

    private Ticket createTicket(TicketStatus status, Instant enteredAt) {
        Ticket ticket = mock(Ticket.class);
        OrderItem orderItem = mock(OrderItem.class);
        TicketProduct product = mock(TicketProduct.class);

        when(ticket.getId()).thenReturn(TICKET_ID);
        when(ticket.getCode()).thenReturn("TKT-test-code");
        when(ticket.getName()).thenReturn("발급 당시 종일이용권");
        when(ticket.getType()).thenReturn(TicketType.ALL_DAY);
        when(ticket.getValidDate()).thenReturn(VALID_DATE);
        when(ticket.getStatus()).thenReturn(status);
        when(ticket.getEnteredAt()).thenReturn(enteredAt);

        when(ticket.getOrderItem()).thenReturn(orderItem);
        when(orderItem.getTicketProduct()).thenReturn(product);
        when(product.getId()).thenReturn(PRODUCT_ID);

        return ticket;
    }
}