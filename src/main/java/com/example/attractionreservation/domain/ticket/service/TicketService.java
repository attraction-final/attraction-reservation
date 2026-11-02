package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.dto.*;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.event.TicketIssuedEvent;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    // 티켓 발급 정보를 Listener에 전달하는 Spring event 발행자
    private final ApplicationEventPublisher applicationEventPublisher;

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
        // 발급 로그에 필요한 값만 담아 엔티티를 Listener에 직접 전달하지 않는다.
        TicketIssuedEvent ticketIssuedEvent = new TicketIssuedEvent(order.getId(), order.getUser().getId(), tickets.size());
        // 현재 transaction 안에서 발행하며, commit 이후 실행 여부는 Listener에서 설정한다.
        applicationEventPublisher.publishEvent(ticketIssuedEvent);
    }

    // 환불 - 요청한 티켓을 잠가서 조회한다. 없거나 해당 주문 상품의 티켓이 아니면 예외가 발생한다.
    @Transactional
    public List<Ticket> findRefundTicketsForUpdate(Collection<Long> orderItemIds, Collection<Long> ticketIds) {
        List<Ticket> tickets = ticketRepository.findAllByIdInAndOrderItemIdInForUpdate(ticketIds, orderItemIds);

        if (tickets.size() != ticketIds.size()) {
            throw new BusinessException(ErrorCode.REFUND_INVALID_TICKET);
        }

        return tickets;
    }

    // 환불 - 주문 상품들의 미사용 티켓을 잠가서 조회한다.
    @Transactional
    public List<Ticket> findUnusedTicketsForUpdate(Collection<Long> orderItemIds) {
        return ticketRepository.findAllByOrderItemIdInAndStatusForUpdate(orderItemIds, TicketStatus.UNUSED);
    }
}
