package com.example.attractionreservation.domain.ticket.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 티켓 발급 transaction의 commit 성공 후 발급 내역을 로그로 남기는 Listener.
 */
@Component
@Slf4j
public class TicketIssuedEventListener {

    // event를 발행한 transaction이 commit된 경우에만 실행하며, rollback되면 실행하지 않는다.
    // transaction 없이 발행한 event는 기본 설정(fallbackExecution = false)에 따라 처리하지 않는다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTicketIssued(TicketIssuedEvent event) {
        // 개별 티켓마다 기록하지 않고 주문별 발급 수량을 한 번에 기록한다.
        log.info("event=TICKETS_ISSUED orderId={} userId={} count={}",
                event.orderId(),
                event.userId(),
                event.count()
                );
    }
}