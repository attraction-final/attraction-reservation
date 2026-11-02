package com.example.attractionreservation.domain.ticket.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.event.TicketIssuedEvent;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TicketIssueServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private TicketService ticketService;

    @Captor
    private ArgumentCaptor<List<Ticket>> ticketsCaptor;

    @Captor
    private ArgumentCaptor<TicketIssuedEvent> eventCaptor;

    private Order order;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("ticket-owner@test.com")
                .password("test-password")
                .nickname("테스트회원")
                .build();
        ReflectionTestUtils.setField(user, "id", 10L);

        order = Order.create(user, 130000, LocalDate.of(2026, 10, 30));
        ReflectionTestUtils.setField(order, "id", 20L);

        TicketProduct allDayProduct = new TicketProduct(
                "종일권", 50000, "종일권 설명", TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        TicketProduct afternoonProduct = new TicketProduct(
                "오후권", 30000, "오후권 설명", TicketType.AFTERNOON, 100, TicketProductStatus.ON_SALE
        );
        order.addOrderItem(allDayProduct, 2);
        order.addOrderItem(afternoonProduct, 1);
    }

    @Test
    @DisplayName("주문 상품 수량만큼 티켓을 저장한 뒤 주문과 소유자 및 전체 발급 수량을 event로 한 번 전달한다")
    void issueTickets_MultipleOrderItems_SavesTicketsAndPublishesOneEvent() {
        // given
        var inOrder = inOrder(ticketRepository, applicationEventPublisher);

        // when
        ticketService.issueTickets(order);

        // then
        inOrder.verify(ticketRepository).saveAll(ticketsCaptor.capture());
        inOrder.verify(applicationEventPublisher).publishEvent(eventCaptor.capture());
        inOrder.verifyNoMoreInteractions();

        List<Ticket> tickets = ticketsCaptor.getValue();
        assertThat(tickets).hasSize(3);
        assertThat(tickets).allSatisfy(ticket -> {
            assertThat(ticket.getValidDate()).isEqualTo(order.getVisitDate());
            assertThat(ticket.getStatus()).isEqualTo(TicketStatus.UNUSED);
            assertThat(ticket.getOrderItem().getOrder()).isSameAs(order);
        });
        assertThat(tickets).extracting(Ticket::getName)
                .containsExactly("종일권", "종일권", "오후권");
        assertThat(tickets).extracting(Ticket::getType)
                .containsExactly(TicketType.ALL_DAY, TicketType.ALL_DAY, TicketType.AFTERNOON);
        assertThat(eventCaptor.getValue()).isEqualTo(new TicketIssuedEvent(20L, 10L, 3));
    }

    @Test
    @DisplayName("티켓 저장이 실패하면 예외를 전달하고 발급 event를 발행하지 않는다")
    void issueTickets_SaveFails_DoesNotPublishEvent() {
        // given
        IllegalStateException saveFailure = new IllegalStateException("티켓 저장 실패");
        doThrow(saveFailure).when(ticketRepository).saveAll(anyList());

        // when & then
        assertThatThrownBy(() -> ticketService.issueTickets(order)).isSameAs(saveFailure);
        verifyNoInteractions(applicationEventPublisher);
    }
}
