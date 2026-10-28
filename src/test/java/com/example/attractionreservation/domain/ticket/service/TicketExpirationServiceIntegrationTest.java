package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.config.JpaConfig;
import com.example.attractionreservation.global.config.QuerydslConfig;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        JpaConfig.class,
        QuerydslConfig.class,
        TicketExpirationService.class,
        TicketExpirationServiceIntegrationTest.FixedClockConfig.class
})
class TicketExpirationServiceIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private static final Instant NOW = Instant.parse("2026-10-28T13:00:00Z");

    @Autowired
    private TicketExpirationService ticketExpirationService;

    @Autowired
    private EntityManager entityManager;

    private OrderItem orderItem;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("ticket-expiration@test.com")
                .password("test-password")
                .nickname("종료테스트회원")
                .build();
        entityManager.persist(user);
        TicketProduct product = new TicketProduct(
                "종일 이용권", 50000, "종료 테스트 상품",
                TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        entityManager.persist(product);
        Order order = Order.create(user, 50000);
        entityManager.persist(order);
        orderItem = OrderItem.create(order, product, 1);
        entityManager.persist(orderItem);
    }

    @Test
    @DisplayName("실제 조회와 dirty checking으로 과거와 당일 티켓을 종료하고 미래 및 환불 티켓은 유지한다")
    void expireTickets_RealRepository_PersistsStateAndClosingTime() {
        // given
        Ticket unused = createTicket(TODAY, TicketStatus.UNUSED);
        Ticket inUse = createTicket(TODAY.minusDays(2), TicketStatus.IN_USE);
        Ticket future = createTicket(TODAY.plusDays(1), TicketStatus.UNUSED);
        Ticket refunded = createTicket(TODAY, TicketStatus.REFUNDED);
        Instant refundedEndTime = NOW.minusSeconds(3600);
        ReflectionTestUtils.setField(refunded, "endTime", refundedEndTime);
        Instant enteredAt = inUse.getEnteredAt();
        flushAndClear();

        // when
        int count = ticketExpirationService.expireTickets(100);
        flushAndClear();

        // then
        assertThat(count).isEqualTo(2);
        assertTicket(unused.getId(), TicketStatus.EXPIRED, closingTime(TODAY));
        assertTicket(inUse.getId(), TicketStatus.USED, closingTime(TODAY.minusDays(2)));
        assertThat(entityManager.find(Ticket.class, inUse.getId()).getEnteredAt()).isEqualTo(enteredAt);
        assertTicket(future.getId(), TicketStatus.UNUSED, null);
        assertTicket(refunded.getId(), TicketStatus.REFUNDED, refundedEndTime);
    }

    @Test
    @DisplayName("한 묶음 처리 후 남은 대상을 다음 호출에서 처리하고 재실행 시 기존 종료 시각을 유지한다")
    void expireTickets_SuccessiveBatches_ProcessesRemainingTargetsOnce() {
        // given
        Ticket first = createTicket(TODAY.minusDays(1), TicketStatus.UNUSED);
        Ticket second = createTicket(TODAY, TicketStatus.IN_USE);
        Ticket third = createTicket(TODAY, TicketStatus.UNUSED);
        flushAndClear();

        // when
        int firstCount = ticketExpirationService.expireTickets(2);
        flushAndClear();

        // then
        assertThat(firstCount).isEqualTo(2);
        assertTicket(first.getId(), TicketStatus.EXPIRED, closingTime(TODAY.minusDays(1)));
        assertTicket(second.getId(), TicketStatus.USED, closingTime(TODAY));
        assertTicket(third.getId(), TicketStatus.UNUSED, null);

        // when
        int secondCount = ticketExpirationService.expireTickets(2);
        flushAndClear();
        int repeatedCount = ticketExpirationService.expireTickets(2);
        flushAndClear();

        // then
        assertThat(secondCount).isEqualTo(1);
        assertThat(repeatedCount).isZero();
        assertTicket(first.getId(), TicketStatus.EXPIRED, closingTime(TODAY.minusDays(1)));
        assertTicket(second.getId(), TicketStatus.USED, closingTime(TODAY));
        assertTicket(third.getId(), TicketStatus.EXPIRED, closingTime(TODAY));
    }

    private Ticket createTicket(LocalDate validDate, TicketStatus status) {
        Ticket ticket = new Ticket(validDate, orderItem);
        ReflectionTestUtils.setField(ticket, "status", status);
        if (status == TicketStatus.IN_USE) {
            ReflectionTestUtils.setField(ticket, "enteredAt",
                    validDate.atTime(16, 0).atZone(KstTime.ZONE).toInstant());
        }
        entityManager.persist(ticket);
        return ticket;
    }

    private Instant closingTime(LocalDate date) {
        return date.atTime(22, 0).atZone(KstTime.ZONE).toInstant();
    }

    private void assertTicket(Long id, TicketStatus expectedStatus, Instant expectedEndTime) {
        Ticket ticket = entityManager.find(Ticket.class, id);
        assertThat(ticket.getStatus()).isEqualTo(expectedStatus);
        assertThat(ticket.getEndTime()).isEqualTo(expectedEndTime);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
