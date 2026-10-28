package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketExpirationServiceTest {

    private static final int BATCH_SIZE = 100;

    @Mock
    private TicketRepository ticketRepository;

    @ParameterizedTest
    @CsvSource({
            "2026-10-28T21:59:59.999999999, 2026-10-27",
            "2026-10-28T22:00:00, 2026-10-28",
            "2026-10-28T22:00:00.000000001, 2026-10-28",
            "2026-10-29T00:00:00, 2026-10-28",
            "2026-10-29T08:30:00, 2026-10-28",
            "2027-01-01T00:00:00, 2026-12-31"
    })
    @DisplayName("Clock의 시간대와 무관하게 KST 폐장 정각 기준으로 조회 날짜를 계산한다")
    void expireTickets_TimeBoundary_PassesClosedDate(String kstNow, String expectedDate) {
        // given
        Instant now = LocalDateTime.parse(kstNow).atZone(KstTime.ZONE).toInstant();
        TicketExpirationService service = serviceAt(now);
        LocalDate closedDate = LocalDate.parse(expectedDate);
        when(ticketRepository.findExpirationTargets(closedDate, BATCH_SIZE)).thenReturn(List.of());

        // when
        int count = service.expireTickets(BATCH_SIZE);

        // then
        assertThat(count).isZero();
        verify(ticketRepository).findExpirationTargets(closedDate, BATCH_SIZE);
    }

    @Test
    @DisplayName("조회한 모든 티켓에 같은 시각을 전달하고 실제 변경된 티켓만 집계한다")
    void expireTickets_MixedResults_CountsChangedTickets() {
        // given
        Instant now = Instant.parse("2026-10-28T13:00:00Z");
        TicketExpirationService service = serviceAt(now);
        Ticket first = mock(Ticket.class);
        Ticket skipped = mock(Ticket.class);
        Ticket last = mock(Ticket.class);
        when(ticketRepository.findExpirationTargets(LocalDate.of(2026, 10, 28), BATCH_SIZE))
                .thenReturn(List.of(first, skipped, last));
        when(first.expireIfClosed(now)).thenReturn(true);
        when(skipped.expireIfClosed(now)).thenReturn(false);
        when(last.expireIfClosed(now)).thenReturn(true);

        // when
        int count = service.expireTickets(BATCH_SIZE);

        // then
        assertThat(count).isEqualTo(2);
        verify(first).expireIfClosed(now);
        verify(skipped).expireIfClosed(now);
        verify(last).expireIfClosed(now);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("조회 건수가 1 미만이면 Repository 호출 없이 예외를 반환한다")
    void expireTickets_InvalidBatchSize_RejectsBeforeQuery(int batchSize) {
        // given
        TicketExpirationService service = serviceAt(Instant.parse("2026-10-28T13:00:00Z"));

        // when & then
        assertThatThrownBy(() -> service.expireTickets(batchSize))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verifyNoInteractions(ticketRepository);
    }

    @Test
    @DisplayName("조회 실패는 성공 건수로 바꾸지 않고 호출자에게 전달한다")
    void expireTickets_QueryFailure_PropagatesException() {
        // given
        TicketExpirationService service = serviceAt(Instant.parse("2026-10-28T13:00:00Z"));
        RuntimeException failure = new IllegalStateException("조회 실패");
        when(ticketRepository.findExpirationTargets(LocalDate.of(2026, 10, 28), BATCH_SIZE))
                .thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> service.expireTickets(BATCH_SIZE)).isSameAs(failure);
    }

    private TicketExpirationService serviceAt(Instant now) {
        // 시스템 기본 시간대를 따르지 않고 명시적으로 KST 변환하는지 검증한다.
        return new TicketExpirationService(ticketRepository, Clock.fixed(now, ZoneOffset.UTC));
    }
}
