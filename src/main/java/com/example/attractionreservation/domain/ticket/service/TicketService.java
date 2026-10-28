package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.dto.*;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    // 내 티켓 상세 조회
    public SearchTicketDetailResponse findTicketById(Long userId, Long ticketId) {
        Ticket ticket = ticketRepository.findByIdAndOwner_Id(ticketId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        return SearchTicketDetailResponse.from(ticket);
    }

    // 티켓 입장 처리
    // TODO: 동시성 처리 필요
    @Transactional
    public CreateTicketEntryResponse createTicketEntry(CreateTicketEntryRequest request) {

        // 티켓 코드로 입장권을 조회
        Ticket ticket = ticketRepository.findByCode(request.ticketCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        // 현재 순간을 저장
        Instant now = Instant.now();

        // 검증에 성공하면 UNUSED에서 IN_USE로 변경하고 서버 시각을 enteredAt에 기록한다.
        ticket.entryTicket(now);

        return CreateTicketEntryResponse.from(ticket);
    }

    // 예약 - 티켓 찾기
    public Ticket findByTicketId(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
    }

    // 티켓 발급
    @Transactional
    public void issueTickets(Order order) {
        List<Ticket> tickets = new ArrayList<>();

        for (OrderItem orderItem: order.getOrderItems()) {
            for (int count = 0; count < orderItem.getQuantity(); count++) {
                tickets.add(new Ticket(order.getVisitDate(), orderItem));
            }
        }

        ticketRepository.saveAll(tickets);
    }
}
