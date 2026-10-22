package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.dto.SearchTicketCondition;
import com.example.attractionreservation.domain.ticket.dto.SearchTicketResponse;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;

    // 내 티켓 목록 조회
    public Page<SearchTicketResponse> findTicketAll(Long userId, SearchTicketCondition condition, Pageable stablePageable) {
        Page<Ticket> tickets = ticketRepository.findAllByUserIdWithCondition(
                userId,
                condition.name(),
                condition.type(),
                condition.status(),
                stablePageable
        );

        return tickets.map(SearchTicketResponse::from);
    }
}
