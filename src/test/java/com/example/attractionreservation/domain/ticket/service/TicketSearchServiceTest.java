package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.dto.SearchTicketCondition;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketSearchServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private TicketService ticketService;

    @Test
    @DisplayName("사용자 ID와 검색 조건을 전달하고 티켓과 페이지 정보를 반환한다")
    void findTicketAll_WithConditions_Success() {
        // given
        Long userId = 7L;
        SearchTicketCondition condition = new SearchTicketCondition(
                "종일", TicketType.ALL_DAY, TicketStatus.UNUSED
        );
        Pageable pageable = PageRequest.of(
                1, 2,
                Sort.by(Sort.Order.desc("status"), Sort.Order.desc("id"))
        );

        Ticket ticket = mock(Ticket.class);
        LocalDate validDate = LocalDate.of(2026, 10, 25);

        when(ticket.getId()).thenReturn(10L);
        when(ticket.getCode()).thenReturn("TKT-test-code");
        when(ticket.getName()).thenReturn("종일이용권");
        when(ticket.getType()).thenReturn(TicketType.ALL_DAY);
        when(ticket.getValidDate()).thenReturn(validDate);
        when(ticket.getStatus()).thenReturn(TicketStatus.UNUSED);

        when(ticketRepository.findAllByUserIdWithCondition(
                userId, "종일", TicketType.ALL_DAY, TicketStatus.UNUSED, pageable
        )).thenReturn(new PageImpl<>(List.of(ticket), pageable, 3));

        // when
        Page<SearchTicketResponse> result =
                ticketService.findTicketAll(userId, condition, pageable);

        // then
        assertThat(result.getContent()).containsExactly(
                new SearchTicketResponse(
                        10L,
                        "TKT-test-code",
                        "종일이용권",
                        TicketType.ALL_DAY,
                        validDate,
                        TicketStatus.UNUSED
                )
        );
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getSort()).isEqualTo(pageable.getSort());

        verify(ticketRepository).findAllByUserIdWithCondition(
                userId, "종일", TicketType.ALL_DAY, TicketStatus.UNUSED, pageable
        );
    }

    @Test
    @DisplayName("검색 조건과 조회 결과가 없으면 빈 페이지를 반환한다")
    void findTicketAll_NoResults_EmptyPage() {
        // given
        Long userId = 7L;
        SearchTicketCondition condition =
                new SearchTicketCondition(null, null, null);
        Pageable pageable = PageRequest.of(0, 10);

        when(ticketRepository.findAllByUserIdWithCondition(
                userId, null, null, null, pageable
        )).thenReturn(Page.empty(pageable));

        // when
        Page<SearchTicketResponse> result =
                ticketService.findTicketAll(userId, condition, pageable);

        // then
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
        assertThat(result.getNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(10);

        verify(ticketRepository).findAllByUserIdWithCondition(
                userId, null, null, null, pageable
        );
    }
}