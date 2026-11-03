package com.example.attractionreservation.domain.ticket.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.example.attractionreservation.domain.ticket.service.TicketExpirationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketExpirationSchedulerTest {

    @Mock
    private TicketExpirationService ticketExpirationService;

    private Logger logger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(TicketExpirationScheduler.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.INFO);

        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(originalLevel);
    }

    @Test
    @DisplayName("Scheduler는 설정된 조회 건수로 Service를 한 번 호출하고 처리 건수와 소요 시간을 INFO로 기록한다")
    void expireTickets_ConfiguredBatchSize_CallsServiceOnce() {
        // given
        int batchSize = 37;
        TicketExpirationScheduler scheduler = new TicketExpirationScheduler(ticketExpirationService, batchSize);
        when(ticketExpirationService.expireTickets(batchSize)).thenReturn(12);

        // when
        scheduler.expireTickets();

        // then
        verify(ticketExpirationService).expireTickets(batchSize);
        assertThat(appender.list).singleElement()
                .satisfies(logEvent -> assertSuccessLog(logEvent, 12));
    }

    @Test
    @DisplayName("실패 시 예외를 포함한 ERROR 로그를 한 번 남기고 다음 실행에서는 성공 로그를 기록한다")
    void expireTickets_ServiceFailure_AllowsNextInvocation() {
        // given
        int batchSize = 100;
        TicketExpirationScheduler scheduler = new TicketExpirationScheduler(ticketExpirationService, batchSize);
        IllegalStateException failure = new IllegalStateException("처리 실패");
        when(ticketExpirationService.expireTickets(batchSize))
                .thenThrow(failure)
                .thenReturn(1);

        // when & then
        assertThatCode(scheduler::expireTickets).doesNotThrowAnyException();
        assertThat(appender.list).singleElement().satisfies(logEvent -> {
            assertThat(logEvent.getLevel()).isEqualTo(Level.ERROR);
            assertThat(logEvent.getFormattedMessage()).matches(
                    "event=TICKET_EXPIRATION_BATCH_FINISHED durationMs=\\d+ result=FAILURE errorCode=INTERNAL_SERVER_ERROR"
            );
            assertThat(logEvent.getThrowableProxy()).isInstanceOfSatisfying(
                    ThrowableProxy.class,
                    proxy -> assertThat(proxy.getThrowable()).isSameAs(failure)
            );
        });

        assertThatCode(scheduler::expireTickets).doesNotThrowAnyException();
        verify(ticketExpirationService, times(2)).expireTickets(batchSize);
        assertThat(appender.list).hasSize(2);
        assertSuccessLog(appender.list.get(1), 1);
    }

    @Test
    @DisplayName("종료 대상 티켓이 없어도 처리 건수 0의 성공 로그를 기록한다")
    void expireTickets_NoTargets_LogsSuccessWithZeroCount() {
        // given
        int batchSize = 100;
        TicketExpirationScheduler scheduler = new TicketExpirationScheduler(ticketExpirationService, batchSize);
        when(ticketExpirationService.expireTickets(batchSize)).thenReturn(0);

        // when
        scheduler.expireTickets();

        // then
        verify(ticketExpirationService).expireTickets(batchSize);
        assertThat(appender.list).singleElement()
                .satisfies(logEvent -> assertSuccessLog(logEvent, 0));
    }

    private void assertSuccessLog(ILoggingEvent logEvent, int count) {
        assertThat(logEvent.getLevel()).isEqualTo(Level.INFO);
        // 실제 소요 시간은 고정하지 않고, 0 이상의 정수 밀리초 형식인지만 검증한다.
        assertThat(logEvent.getFormattedMessage()).matches(
                "event=TICKET_EXPIRATION_BATCH_FINISHED count=" + count + " durationMs=\\d+ result=SUCCESS"
        );
        assertThat(logEvent.getThrowableProxy()).isNull();
    }
}
