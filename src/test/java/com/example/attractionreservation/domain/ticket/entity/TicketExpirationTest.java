package com.example.attractionreservation.domain.ticket.entity;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketExpirationTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 28);
    private static final Instant CLOSE_TIME =
            VISIT_DATE.atTime(22, 0).atZone(KstTime.ZONE).toInstant();

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"UNUSED", "IN_USE"})
    @DisplayName("폐장 직전에는 티켓 상태와 입장 시각 및 종료 시각을 변경하지 않는다")
    void expireIfClosed_BeforeClosing_PreservesValues(TicketStatus status) {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY, status);
        Instant enteredAt = ticket.getEnteredAt();

        // when
        boolean result = ticket.expireIfClosed(CLOSE_TIME.minusNanos(1));

        // then
        assertThat(result).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(status);
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
        assertThat(ticket.getEndTime()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "ALL_DAY, UNUSED, EXPIRED",
            "ALL_DAY, IN_USE, USED",
            "AFTERNOON, UNUSED, EXPIRED",
            "AFTERNOON, IN_USE, USED"
    })
    @DisplayName("권종과 관계없이 폐장 정각부터 상태를 전환하고 KST 방문일 22시를 기록한다")
    void expireIfClosed_AtClosing_ChangesStatus(
            TicketType type, TicketStatus before, TicketStatus after
    ) {
        // given
        Ticket ticket = createTicket(type, before);
        Instant enteredAt = ticket.getEnteredAt();

        // when
        boolean result = ticket.expireIfClosed(CLOSE_TIME);

        // then
        assertThat(result).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(after);
        assertThat(ticket.getEndTime()).isEqualTo(Instant.parse("2026-10-28T13:00:00Z"));
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
    }

    @ParameterizedTest
    @CsvSource({"UNUSED, EXPIRED", "IN_USE, USED"})
    @DisplayName("처리가 여러 날 늦어져도 종료 시각은 실행 시각이 아닌 방문일 폐장 시각이다")
    void expireIfClosed_DelayedExecution_RecordsOriginalClosingTime(
            TicketStatus before, TicketStatus after
    ) {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY, before);
        Instant delayedNow = VISIT_DATE.plusDays(3).atStartOfDay().atZone(KstTime.ZONE).toInstant();

        // when
        boolean result = ticket.expireIfClosed(delayedNow);

        // then
        assertThat(result).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(after);
        assertThat(ticket.getEndTime()).isEqualTo(CLOSE_TIME);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"USED", "EXPIRED", "REFUNDED"})
    @DisplayName("이미 종료되거나 환불된 티켓은 기존 상태와 시각을 유지한다")
    void expireIfClosed_TerminalStatus_PreservesValues(TicketStatus status) {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY, status);
        Instant previousEndTime = CLOSE_TIME.minusSeconds(3600);
        ReflectionTestUtils.setField(ticket, "endTime", previousEndTime);

        // when
        boolean result = ticket.expireIfClosed(CLOSE_TIME.plusSeconds(60));

        // then
        assertThat(result).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(status);
        assertThat(ticket.getEndTime()).isEqualTo(previousEndTime);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"UNUSED", "IN_USE"})
    @DisplayName("같은 티켓을 다시 종료 처리해도 두 번째 호출은 변경하지 않는다")
    void expireIfClosed_RepeatedExecution_ReturnsFalse(TicketStatus status) {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY, status);
        ticket.expireIfClosed(CLOSE_TIME);
        TicketStatus completedStatus = ticket.getStatus();

        // when
        boolean result = ticket.expireIfClosed(CLOSE_TIME.plusSeconds(60));

        // then
        assertThat(result).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(completedStatus);
        assertThat(ticket.getEndTime()).isEqualTo(CLOSE_TIME);
    }

    @Test
    @DisplayName("종료 처리 기준 시각이 없으면 예외를 반환하고 티켓을 변경하지 않는다")
    void expireIfClosed_NullTime_ThrowsException() {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY, TicketStatus.IN_USE);
        Instant enteredAt = ticket.getEnteredAt();

        // when & then
        assertThatThrownBy(() -> ticket.expireIfClosed(null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_TICKET_EXPIRATION_TIME));
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_USE);
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
        assertThat(ticket.getEndTime()).isNull();
    }

    private Ticket createTicket(TicketType type, TicketStatus status) {
        TicketProduct product = mock(TicketProduct.class);
        when(product.getName()).thenReturn("테스트 입장권");
        when(product.getType()).thenReturn(type);
        OrderItem item = mock(OrderItem.class);
        when(item.getTicketProduct()).thenReturn(product);
        Ticket ticket = new Ticket(VISIT_DATE, item);
        ReflectionTestUtils.setField(ticket, "status", status);
        if (status == TicketStatus.IN_USE) {
            ReflectionTestUtils.setField(ticket, "enteredAt",
                    VISIT_DATE.atTime(LocalTime.of(16, 0)).atZone(KstTime.ZONE).toInstant());
        }
        return ticket;
    }
}
