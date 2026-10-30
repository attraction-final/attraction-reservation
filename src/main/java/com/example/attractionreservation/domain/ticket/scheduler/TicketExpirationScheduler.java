package com.example.attractionreservation.domain.ticket.scheduler;

import com.example.attractionreservation.domain.ticket.service.TicketExpirationService;
import com.example.attractionreservation.global.exception.ErrorCode;
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
        long startedAt = System.nanoTime();

        try {
            int count = ticketExpirationService.expireTickets(batchSize);
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            log.info("event=TICKET_EXPIRATION_BATCH_FINISHED count={} durationMs={} result=SUCCESS",
                    count, durationMs
                    );
        } catch (RuntimeException e) {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            log.error("event=TICKET_EXPIRATION_BATCH_FINISHED durationMs={} result=FAILURE errorCode={}",
                    durationMs, ErrorCode.INTERNAL_SERVER_ERROR.name(), e);
        }
    }

}
