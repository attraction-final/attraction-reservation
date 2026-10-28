package com.example.attractionreservation.domain.ticket.scheduler;

import com.example.attractionreservation.domain.ticket.service.TicketExpirationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TicketExpirationScheduler {

    private final TicketExpirationService ticketExpirationService;
    private final int batchSize;

    public TicketExpirationScheduler(
            TicketExpirationService ticketExpirationService,
            @Value("${ticket.expiration.scheduler.batch-size}") int batchSize
    ) {
        this.ticketExpirationService = ticketExpirationService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ticket.expiration.scheduler.fixed-delay}")
    public void expireTickets() {
        try {
            int count = ticketExpirationService.expireTickets(batchSize);
            log.info("티켓 자동 종료 처리 완료: count={}", count);
        } catch (RuntimeException e) {
            log.error("티켓 자동 종료 처리 실패", e);
        }
    }

}
