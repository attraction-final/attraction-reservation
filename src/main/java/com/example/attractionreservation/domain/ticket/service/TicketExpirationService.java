package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TicketExpirationService {

    private final TicketRepository ticketRepository;
    private final Clock clock;

    // TODO: 동시성
    public int expireTickets(int batchSize) {
        // 현재 시각 조회
        // 주입받은 clock에서 Instant now 구하기
        Instant now = clock.instant();

        // KST로 변환
        LocalDateTime currentTime = KstTime.toLocalDateTime(now);

        LocalDate closedDate = currentTime.toLocalDate();
        if (currentTime.toLocalTime().isBefore(LocalTime.of(22, 0))) {
            closedDate = closedDate.minusDays(1);
        }

        // repository 호출
        if (batchSize < 1) {
            throw new BusinessException(ErrorCode.INVALID_INPUT_VALUE);
        }
        List<Ticket> tickets = ticketRepository.findExpirationTargets(closedDate, batchSize);

        // 각 티켓의 expireIfClosed호출
        int count = 0;
        for (Ticket ticket : tickets) {
            if (ticket.expireIfClosed(now)) {
                count++;
            }
        }
        return count;
    }
}
