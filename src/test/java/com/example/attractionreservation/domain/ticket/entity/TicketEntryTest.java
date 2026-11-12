package com.example.attractionreservation.domain.ticket.entity;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static com.example.attractionreservation.domain.ticket.entity.QTicket.ticket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketEntryTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 22);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @ParameterizedTest
    @CsvSource({
            "ALL_DAY, 10:00",
            "ALL_DAY, 14:59:59",
            "ALL_DAY, 21:59:59.999999999",
            "AFTERNOON, 15:00",
            "AFTERNOON, 21:59:59.999999999"
    })
    void 이용가능시간이면_입장에_성공한다(TicketType type, String time) {
        Ticket ticket = createTicket(type);
        Instant now = atKst(VISIT_DATE, time);

        ticket.entryTicket(now);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.IN_USE);
        assertThat(ticket.getEnteredAt()).isEqualTo(now);
    }

    @ParameterizedTest
    @CsvSource({
            "ALL_DAY, 09:59:59.999999999",
            "AFTERNOON, 14:59:59.999999999"
    })
    void 권종별_입장시작시간_전에는_입장할수없다(
            TicketType type,
            String time
    ) {
        Ticket ticket = createTicket(type);

        assertRejected(
                ticket,
                atKst(VISIT_DATE, time),
                ErrorCode.TICKET_ENTRY_TIME_NOT_ALLOWED
        );
        assertUnchanged(ticket, TicketStatus.UNUSED, null);
    }

    @ParameterizedTest
    @EnumSource(TicketType.class)
    void 폐장정각부터_입장할수없다(TicketType type) {
        Ticket ticket = createTicket(type);

        assertRejected(
                ticket,
                atKst(VISIT_DATE, "22:00"),
                ErrorCode.TICKET_ENTRY_TIME_NOT_ALLOWED
        );
        assertUnchanged(ticket, TicketStatus.UNUSED, null);
    }

    @ParameterizedTest
    @EnumSource(TicketType.class)
    void 폐장이후에는_입장할수없다(TicketType type) {
        Ticket ticket = createTicket(type);

        assertRejected(
                ticket,
                atKst(VISIT_DATE, "22:01"),
                ErrorCode.TICKET_ENTRY_TIME_NOT_ALLOWED
        );
        assertUnchanged(ticket, TicketStatus.UNUSED, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void 방문일이_아니면_입장할수없다(int days) {
        Ticket ticket = createTicket(TicketType.ALL_DAY);

        assertRejected(
                ticket,
                atKst(VISIT_DATE.plusDays(days), "16:00"),
                ErrorCode.TICKET_ENTRY_DATE_MISMATCH
        );
        assertUnchanged(ticket, TicketStatus.UNUSED, null);
    }

    @ParameterizedTest
    @EnumSource(
            value = TicketStatus.class,
            names = "UNUSED",
            mode = EnumSource.Mode.EXCLUDE
    )
    void 미사용이_아닌_티켓은_입장할수없다(TicketStatus status) {
        Ticket ticket = createTicket(TicketType.ALL_DAY);
        ReflectionTestUtils.setField(ticket, "status", status);

        assertRejected(
                ticket,
                atKst(VISIT_DATE, "16:00"),
                ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS
        );
        assertUnchanged(ticket, status, null);
    }

    @Test
    void 재입장에_실패해도_최초입장시각은_유지된다() {
        Ticket ticket = createTicket(TicketType.ALL_DAY);
        Instant firstEntry = atKst(VISIT_DATE, "10:00");
        ticket.entryTicket(firstEntry);

        assertRejected(
                ticket,
                atKst(VISIT_DATE, "11:00"),
                ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS
        );

        assertUnchanged(ticket, TicketStatus.IN_USE, firstEntry);
    }

    @Test
    void UTC날짜가_달라도_한국방문일을_기준으로_판단한다() {
        Ticket ticket = createTicket(TicketType.ALL_DAY);

        // UTC 10월 21일 16시 = 한국 10월 22일 01시
        Instant now = Instant.parse("2026-10-21T16:00:00Z");

        // 방문일 오류가 아니라 개장 전 시간 오류여야 한다.
        assertRejected(
                ticket,
                now,
                ErrorCode.TICKET_ENTRY_TIME_NOT_ALLOWED
        );
        assertUnchanged(ticket, TicketStatus.UNUSED, null);
    }

    @Test
    @DisplayName("입장 처리 시각이 없으면 예외가 발생하고 티켓 상태를 유지한다")
    void entryTicket_nullTime() {
        // given
        Ticket ticket = createTicket(TicketType.ALL_DAY);

        // when & then
        assertThatThrownBy(() -> ticket.entryTicket(null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_TICKET_ENTRY_TIME)
                );

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.UNUSED);
        assertThat(ticket.getEnteredAt()).isNull();
    }

    private Ticket createTicket(TicketType type) {
        TicketProduct product = mock(TicketProduct.class);
        when(product.getName()).thenReturn("테스트 입장권");
        when(product.getType()).thenReturn(type);

        OrderItem orderItem = mock(OrderItem.class);
        when(orderItem.getTicketProduct()).thenReturn(product);

        return new Ticket(VISIT_DATE, orderItem);
    }

    private Instant atKst(LocalDate date, String time) {
        return LocalDateTime.of(date, LocalTime.parse(time))
                .atZone(KST)
                .toInstant();
    }

    private void assertRejected(
            Ticket ticket,
            Instant now,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(() -> ticket.entryTicket(now))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(errorCode)
                );
    }

    private void assertUnchanged(
            Ticket ticket,
            TicketStatus status,
            Instant enteredAt
    ) {
        assertThat(ticket.getStatus()).isEqualTo(status);
        assertThat(ticket.getEnteredAt()).isEqualTo(enteredAt);
    }
}