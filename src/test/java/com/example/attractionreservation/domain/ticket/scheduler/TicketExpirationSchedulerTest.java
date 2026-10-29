package com.example.attractionreservation.domain.ticket.scheduler;

import com.example.attractionreservation.domain.ticket.service.TicketExpirationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketExpirationSchedulerTest {

    @Mock
    private TicketExpirationService ticketExpirationService;

    @Test
    @DisplayName("Scheduler는 생성자로 전달받은 조회 건수로 처리 Service를 한 번 호출한다")
    void expireTickets_ConfiguredBatchSize_CallsServiceOnce() {
        // given
        int batchSize = 37;
        TicketExpirationScheduler scheduler = new TicketExpirationScheduler(ticketExpirationService, batchSize);
        when(ticketExpirationService.expireTickets(batchSize)).thenReturn(12);

        // when
        scheduler.expireTickets();

        // then
        verify(ticketExpirationService).expireTickets(batchSize);
    }

    @Test
    @DisplayName("한 번의 실행이 실패해도 예외를 외부로 던지지 않고 다음 호출에서 다시 처리한다")
    void expireTickets_ServiceFailure_AllowsNextInvocation() {
        // given
        int batchSize = 100;
        TicketExpirationScheduler scheduler = new TicketExpirationScheduler(ticketExpirationService, batchSize);
        when(ticketExpirationService.expireTickets(batchSize))
                .thenThrow(new IllegalStateException("처리 실패"))
                .thenReturn(1);

        // when & then
        assertThatCode(scheduler::expireTickets).doesNotThrowAnyException();
        assertThatCode(scheduler::expireTickets).doesNotThrowAnyException();
        verify(ticketExpirationService, times(2)).expireTickets(batchSize);
    }
}
