package com.example.attractionreservation.domain.refund.service;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.refund.dto.RefundResponse;
import com.example.attractionreservation.domain.refund.dto.RefundedQuantity;
import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.repository.RefundItemRepository;
import com.example.attractionreservation.domain.refund.repository.RefundRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RefundServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADULT_ITEM_ID = 100L;
    private static final Long CHILD_ITEM_ID = 200L;
    private static final String PG_ID = "pg-id";
    private static final Instant NOW = Instant.parse("2026-10-22T01:00:00Z");
    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 25);
    // 입장일 전날 23:59:59 KST / 입장일 00:00 KST
    private static final Instant DAY_BEFORE_VISIT_LAST_MOMENT = Instant.parse("2026-10-24T14:59:59Z");
    private static final Instant VISIT_DAY_START = Instant.parse("2026-10-24T15:00:00Z");

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private RefundItemRepository refundItemRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private TicketService ticketService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RefundService refundService;

    private Payment payment;

    @BeforeEach
    void setUp() {
        payment = createPayment(PaymentStatus.PAID);
    }

    @Test
    @DisplayName("부분 환불: 환불 가능 수량 안에서 티켓을 요청하면 REQUESTED 환불을 저장하고 환불 금액을 합산한다.")
    void createRefund_withinRefundableQuantity_Saved() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = new ArrayList<>(tickets(adultItem(payment), 1L, 3, TicketStatus.UNUSED));
        tickets.addAll(tickets(childItem(payment), 11L, 1, TicketStatus.UNUSED));
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        Long refundId = refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        assertThat(refundId).isEqualTo(1L);
        Refund saved = captureSavedRefund();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(saved.getRefundAmount()).isEqualTo(35000);
        assertThat(saved.getRefundItems())
                .extracting(item -> item.getOrderItem().getId(), RefundItem::getQuantity)
                .containsExactlyInAnyOrder(tuple(ADULT_ITEM_ID, 3), tuple(CHILD_ITEM_ID, 1));
    }

    @Test
    @DisplayName("환불 요청을 저장하면 환불 ID·사용자·금액을 INFO 로그로 남기고 환불 사유는 남기지 않는다.")
    void createRefund_saved_LogRequested(CapturedOutput output) {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 2, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        assertThat(output.getAll().lines().filter(line -> line.contains("event=REFUND_REQUESTED")))
                .singleElement().asString().contains("INFO")
                .contains("refundId=1 pgId=" + payment.getPgId() + " targetUserId=" + USER_ID + " amount=20000")
                .doesNotContain(" userId=", " adminId=");
        assertThat(output).doesNotContain("단순 변심");
    }

    @Test
    @DisplayName("부분 환불: 요청한 티켓은 환불 상태가 된다.")
    void createRefund_requestedTickets_Refunded() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 2, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        assertThat(tickets).extracting(Ticket::getStatus).containsOnly(TicketStatus.REFUNDED);
    }

    @Test
    @DisplayName("부분 환불: 주문의 주문 상품 ID와 요청한 티켓 ID로 티켓을 잠가 조회한다.")
    void createRefund_findTicketsWithOrderItems() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 1, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, List.of(1L), "단순 변심", NOW);

        //then
        verify(ticketService).findRefundTicketsForUpdate(Set.of(ADULT_ITEM_ID, CHILD_ITEM_ID), Set.of(1L));
    }

    @Test
    @DisplayName("부분 환불: 이미 환불 요청된 수량을 뺀 나머지만큼은 환불할 수 있다.")
    void createRefund_remainingQuantity_Saved() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 1, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of(new RefundedQuantity(ADULT_ITEM_ID, 2L)));
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        assertThat(captureSavedRefund().getRefundAmount()).isEqualTo(10000);
    }

    @Test
    @DisplayName("부분 환불: 이미 환불 요청된 수량을 빼면 요청 수량이 남지 않으면 환불하지 않고 티켓도 그대로 둔다.")
    void createRefund_exceedRemainingQuantity_Fail() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 2, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of(new RefundedQuantity(ADULT_ITEM_ID, 2L)));
        List<Long> ticketIds = ticketIds(tickets);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
        verify(refundRepository, never()).save(any());
        assertThat(tickets).extracting(Ticket::getStatus).containsOnly(TicketStatus.UNUSED);
    }

    @Test
    @DisplayName("환불 가능 수량은 재시도 대기·DEAD를 포함한 모든 상태의 환불을 합산해 계산한다.")
    void createRefund_sumAllStatuses() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 1, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        verify(refundItemRepository).sumQuantityByOrderItemIdInAndStatusIn(
                Set.of(ADULT_ITEM_ID), List.of(RefundStatus.values()));
    }

    @Test
    @DisplayName("부분 환불: 없는 티켓이거나 해당 주문의 티켓이 아니면 환불하지 않는다.")
    void createRefund_ticketNotInOrder_Fail() {
        //given
        givenPayment(payment);
        given(ticketService.findRefundTicketsForUpdate(anySet(), anySet()))
                .willThrow(new BusinessException(ErrorCode.REFUND_INVALID_TICKET));
        List<Long> ticketIds = List.of(999L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_TICKET.getMessage());
        verify(refundRepository, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_USE", "USED", "EXPIRED", "REFUNDED"})
    @DisplayName("부분 환불: 미사용 티켓이 아니면 환불하지 않는다.")
    void createRefund_notUnusedTicket_Fail(TicketStatus status) {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 1, status);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        List<Long> ticketIds = ticketIds(tickets);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.TICKET_NOT_REFUNDABLE.getMessage());
        verify(refundRepository, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("부분 환불: 환불할 티켓이 없으면 결제를 조회하지 않고 거절한다.")
    void createRefund_emptyTicketIds_Fail(List<Long> ticketIds) {
        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.INVALID_INPUT_VALUE.getMessage());
        verifyNoInteractions(paymentRepository, ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("부분 환불: 같은 티켓이 중복되면 결제를 조회하지 않고 거절한다.")
    void createRefund_duplicateTicketIds_Fail() {
        //given
        List<Long> ticketIds = List.of(1L, 1L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_DUPLICATE_TICKET.getMessage());
        verifyNoInteractions(paymentRepository, ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("다른 사용자의 결제는 없는 결제처럼 환불할 수 없다.")
    void createRefund_otherUser_Fail() {
        //given
        givenPayment(payment);
        List<Long> ticketIds = List.of(1L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(OTHER_USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @ParameterizedTest(name = "전체 환불 여부: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("일반 사용자가 탈퇴한 타인의 결제를 환불 요청하면 탈퇴 상태를 노출하지 않고 없는 결제로 처리한다")
    void createUserRefund_otherOwnerWithdrawn_returnsPaymentNotFound(boolean fullRefund) {
        // given
        payment.getOrder().getUser().withdraw(NOW);
        givenPayment(payment);

        // when & then
        assertThatThrownBy(() -> {
            if (fullRefund) {
                refundService.createFullRefund(OTHER_USER_ID, PG_ID, "단순 변심", NOW);
            } else {
                refundService.createRefund(OTHER_USER_ID, PG_ID, List.of(1L), "단순 변심", NOW);
            }
        }).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND));

        // 소유권 검사에서 거부되므로 티켓 조회·환불 수량 계산·환불 저장은 수행하지 않는다.
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("존재하지 않는 결제는 환불할 수 없다.")
    void createRefund_paymentNotFound_Fail() {
        //given
        given(paymentRepository.findUserIdByPgId(PG_ID)).willReturn(Optional.empty());
        List<Long> ticketIds = List.of(1L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED", "CANCELLED", "REFUNDED"})
    @DisplayName("부분 환불: 결제 완료·부분 환불 상태가 아닌 결제는 환불할 수 없다.")
    void createRefund_notRefundablePayment_Fail(PaymentStatus status) {
        //given
        givenPayment(createPayment(status));
        List<Long> ticketIds = List.of(1L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("부분 환불된 결제는 추가로 환불할 수 있다.")
    void createRefund_partiallyRefundedPayment_Saved() {
        //given
        Payment partiallyRefunded = createPayment(PaymentStatus.PARTIALLY_REFUNDED);
        givenPayment(partiallyRefunded);
        List<Ticket> tickets = tickets(adultItem(partiallyRefunded), 1L, 2, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of(new RefundedQuantity(ADULT_ITEM_ID, 1L)));
        givenSaveReturnsWithId();

        //when
        refundService.createRefund(USER_ID, PG_ID, ticketIds(tickets), "단순 변심", NOW);

        //then
        assertThat(captureSavedRefund().getRefundAmount()).isEqualTo(20000);
    }

    @Test
    @DisplayName("부분 환불: 입장일 전날 마지막 시각에는 환불할 수 있다.")
    void createRefund_dayBeforeVisit_Saved() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 1, TicketStatus.UNUSED);
        givenRefundTickets(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        Long refundId = refundService.createRefund(
                USER_ID, PG_ID, ticketIds(tickets), "단순 변심", DAY_BEFORE_VISIT_LAST_MOMENT);

        //then
        assertThat(refundId).isEqualTo(1L);
    }

    @Test
    @DisplayName("부분 환불: 입장일 당일부터는 환불할 수 없다.")
    void createRefund_onVisitDay_Fail() {
        //given
        givenPayment(payment);
        List<Long> ticketIds = List.of(1L);

        //when & then
        assertThatThrownBy(() -> refundService.createRefund(USER_ID, PG_ID, ticketIds, "단순 변심", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_DEADLINE_PASSED.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("전체 환불: 환불 이력이 없으면 모든 미사용 티켓을 환불하고 티켓을 환불 상태로 바꾼다.")
    void createFullRefund_noPreviousRefund_RefundAll() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = new ArrayList<>(tickets(adultItem(payment), 1L, 3, TicketStatus.UNUSED));
        tickets.addAll(tickets(childItem(payment), 11L, 2, TicketStatus.UNUSED));
        given(ticketService.findUnusedTicketsForUpdate(Set.of(ADULT_ITEM_ID, CHILD_ITEM_ID))).willReturn(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        Long refundId = refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", NOW);

        //then
        assertThat(refundId).isEqualTo(1L);
        Refund saved = captureSavedRefund();
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(saved.getRefundAmount()).isEqualTo(40000);
        assertThat(saved.getRefundItems())
                .extracting(item -> item.getOrderItem().getId(), RefundItem::getQuantity)
                .containsExactlyInAnyOrder(tuple(ADULT_ITEM_ID, 3), tuple(CHILD_ITEM_ID, 2));
        assertThat(tickets).extracting(Ticket::getStatus).containsOnly(TicketStatus.REFUNDED);
    }

    @Test
    @DisplayName("전체 환불: 부분 환불 뒤에는 남은 미사용 티켓만 환불하고 남은 티켓이 없는 상품은 건너뛴다.")
    void createFullRefund_partiallyRefunded_RefundRemainingOnly() {
        //given
        Payment partiallyRefunded = createPayment(PaymentStatus.PARTIALLY_REFUNDED);
        givenPayment(partiallyRefunded);
        List<Ticket> tickets = tickets(adultItem(partiallyRefunded), 2L, 2, TicketStatus.UNUSED);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(tickets);
        givenRefundedQuantities(List.of(
                new RefundedQuantity(ADULT_ITEM_ID, 1L),
                new RefundedQuantity(CHILD_ITEM_ID, 2L)));
        givenSaveReturnsWithId();

        //when
        refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", NOW);

        //then
        Refund saved = captureSavedRefund();
        assertThat(saved.getRefundAmount()).isEqualTo(20000);
        assertThat(saved.getRefundItems())
                .extracting(item -> item.getOrderItem().getId(), RefundItem::getQuantity)
                .containsExactly(tuple(ADULT_ITEM_ID, 2));
    }

    @Test
    @DisplayName("전체 환불: 일부 티켓으로 입장했으면 남은 미사용 티켓만 환불한다.")
    void createFullRefund_someTicketsEntered_RefundUnusedOnly() {
        //given
        givenPayment(payment);
        List<Ticket> unusedTickets = tickets(adultItem(payment), 3L, 1, TicketStatus.UNUSED);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(unusedTickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", NOW);

        //then
        Refund saved = captureSavedRefund();
        assertThat(saved.getRefundAmount()).isEqualTo(10000);
        assertThat(saved.getRefundItems())
                .extracting(item -> item.getOrderItem().getId(), RefundItem::getQuantity)
                .containsExactly(tuple(ADULT_ITEM_ID, 1));
    }

    @Test
    @DisplayName("전체 환불: 남은 미사용 티켓이 하나도 없으면 환불하지 않는다.")
    void createFullRefund_nothingRemaining_Fail() {
        //given
        givenPayment(payment);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(List.of());

        //when & then
        assertThatThrownBy(() -> refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_INVALID_QUANTITY.getMessage());
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("전체 환불: 입장일 전날 마지막 시각에는 환불할 수 있다.")
    void createFullRefund_dayBeforeVisit_Saved() {
        //given
        givenPayment(payment);
        List<Ticket> tickets = tickets(adultItem(payment), 1L, 3, TicketStatus.UNUSED);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(tickets);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();

        //when
        Long refundId = refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", DAY_BEFORE_VISIT_LAST_MOMENT);

        //then
        assertThat(refundId).isEqualTo(1L);
    }

    @Test
    @DisplayName("전체 환불: 입장일 당일부터는 환불할 수 없다.")
    void createFullRefund_onVisitDay_Fail() {
        //given
        givenPayment(payment);

        //when & then
        assertThatThrownBy(() -> refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_DEADLINE_PASSED.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("전체 환불: 다른 사용자의 결제는 없는 결제처럼 환불할 수 없다.")
    void createFullRefund_otherUser_Fail() {
        //given
        givenPayment(payment);

        //when & then
        assertThatThrownBy(() -> refundService.createFullRefund(OTHER_USER_ID, PG_ID, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED", "CANCELLED", "REFUNDED"})
    @DisplayName("전체 환불: 결제 완료·부분 환불 상태가 아닌 결제는 환불할 수 없다.")
    void createFullRefund_notRefundablePayment_Fail(PaymentStatus status) {
        //given
        givenPayment(createPayment(status));

        //when & then
        assertThatThrownBy(() -> refundService.createFullRefund(USER_ID, PG_ID, "단순 변심", NOW))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT.getMessage());
        verifyNoInteractions(ticketService, refundItemRepository, refundRepository);
    }

    @Test
    @DisplayName("환불 ID로 환불 결과를 조회한다.")
    void findRefundById_exists_Response() {
        //given
        Refund refund = Refund.create(payment, "단순 변심", NOW);
        ReflectionTestUtils.setField(refund, "id", 1L);
        given(refundRepository.findById(1L)).willReturn(Optional.of(refund));

        //when
        RefundResponse response = refundService.findRefundById(1L);

        //then
        assertThat(response).isEqualTo(new RefundResponse(1L, 0, RefundStatus.REQUESTED));
    }

    @Test
    @DisplayName("없는 환불을 조회하면 예외가 발생한다.")
    void findRefundById_notFound_Fail() {
        //given
        given(refundRepository.findById(1L)).willReturn(Optional.empty());

        //when & then
        assertThatThrownBy(() -> refundService.findRefundById(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.REFUND_NOT_FOUND.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 86400})
    @DisplayName("관리자 전체 환불은 회원 소유 결제의 방문일 당일·이후에도 미사용 티켓을 환불 요청한다")
    void createAdminFullRefund_AfterDeadline_SavesUnusedTickets(long secondsAfterVisitStart, CapturedOutput output) {
        // given
        ReflectionTestUtils.setField(payment.getOrder().getUser(), "id", OTHER_USER_ID);
        givenPayment(payment);
        List<Ticket> unused = tickets(adultItem(payment), 1L, 2, TicketStatus.UNUSED);
        given(ticketService.findUnusedTicketsForUpdate(Set.of(ADULT_ITEM_ID, CHILD_ITEM_ID))).willReturn(unused);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();
        Instant requestedAt = VISIT_DAY_START.plusSeconds(secondsAfterVisitStart);

        // when
        Long refundId = refundService.createAdminFullRefund(PG_ID, "관리자 환불", requestedAt);

        // then
        assertThat(refundId).isEqualTo(1L);
        Refund saved = captureSavedRefund();
        assertThat(saved.getPayment()).isSameAs(payment);
        assertThat(saved.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(saved.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(saved.getRefundAmount()).isEqualTo(20000);
        assertThat(unused).extracting(Ticket::getStatus).containsOnly(TicketStatus.REFUNDED);
        // 공통 서비스가 알고 있는 결제 소유자는 요청자가 아닌 대상 회원으로 기록한다.
        assertThat(output.getAll().lines().filter(line -> line.contains("event=REFUND_REQUESTED")))
                .singleElement().asString()
                .contains("targetUserId=" + OTHER_USER_ID)
                .doesNotContain(" userId=", " adminId=");
        var calls = org.mockito.Mockito.inOrder(paymentRepository, ticketService, refundRepository);
        calls.verify(paymentRepository).findWithLockByPgId(PG_ID);
        calls.verify(ticketService).findUnusedTicketsForUpdate(Set.of(ADULT_ITEM_ID, CHILD_ITEM_ID));
        calls.verify(refundRepository).save(any(Refund.class));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 86400})
    @DisplayName("관리자 부분 환불은 회원 소유 결제의 방문일 당일·이후에도 지정한 미사용 티켓만 환불 요청한다")
    void createAdminPartialRefund_AfterDeadline_SavesSelectedTickets(long secondsAfterVisitStart, CapturedOutput output) {
        // given
        ReflectionTestUtils.setField(payment.getOrder().getUser(), "id", OTHER_USER_ID);
        givenPayment(payment);
        List<Ticket> selected = tickets(childItem(payment), 11L, 1, TicketStatus.UNUSED);
        givenRefundTickets(selected);
        givenRefundedQuantities(List.of());
        givenSaveReturnsWithId();
        Instant requestedAt = VISIT_DAY_START.plusSeconds(secondsAfterVisitStart);

        // when
        Long refundId = refundService.createAdminPartialRefund(PG_ID, List.of(11L), "관리자 환불", requestedAt);

        // then
        assertThat(refundId).isEqualTo(1L);
        Refund saved = captureSavedRefund();
        assertThat(saved.getRequestedAt()).isEqualTo(requestedAt);
        assertThat(saved.getRefundAmount()).isEqualTo(5000);
        assertThat(saved.getRefundItems()).singleElement().satisfies(item -> {
            assertThat(item.getOrderItem().getId()).isEqualTo(CHILD_ITEM_ID);
            assertThat(item.getQuantity()).isEqualTo(1);
        });
        assertThat(selected.get(0).getStatus()).isEqualTo(TicketStatus.REFUNDED);
        // 관리자 요청에서도 결제 소유자 ID를 요청자 ID로 오인하지 않도록 검증한다.
        assertThat(output.getAll().lines().filter(line -> line.contains("event=REFUND_REQUESTED")))
                .singleElement().asString()
                .contains("targetUserId=" + OTHER_USER_ID)
                .doesNotContain(" userId=", " adminId=");
        var calls = org.mockito.Mockito.inOrder(paymentRepository, ticketService, refundRepository);
        calls.verify(paymentRepository).findWithLockByPgId(PG_ID);
        calls.verify(ticketService).findRefundTicketsForUpdate(Set.of(ADULT_ITEM_ID, CHILD_ITEM_ID), Set.of(11L));
        calls.verify(refundRepository).save(any(Refund.class));
    }

    @Test
    @DisplayName("관리자 환불도 부분 환불된 결제의 남은 수량을 초과하여 요청할 수 없다")
    void createAdminPartialRefund_ExceedsRemainingQuantity_RejectsRequest() {
        // given
        payment = createPayment(PaymentStatus.PARTIALLY_REFUNDED);
        givenPayment(payment);
        List<Ticket> selected = tickets(adultItem(payment), 1L, 2, TicketStatus.UNUSED);
        givenRefundTickets(selected);
        givenRefundedQuantities(List.of(new RefundedQuantity(ADULT_ITEM_ID, 2L)));

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(
                PG_ID, ticketIds(selected), "관리자 환불", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_INVALID_QUANTITY);
        verify(refundRepository, never()).save(any());
        assertThat(selected).extracting(Ticket::getStatus).containsOnly(TicketStatus.UNUSED);
    }

    @Test
    @DisplayName("관리자 전체 환불도 부분 환불된 결제의 남은 미사용 티켓을 환불할 수 있다")
    void createAdminFullRefund_PartiallyRefundedPayment_SavesRemainingTickets() {
        // given
        payment = createPayment(PaymentStatus.PARTIALLY_REFUNDED);
        givenPayment(payment);
        List<Ticket> unused = tickets(adultItem(payment), 1L, 1, TicketStatus.UNUSED);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(unused);
        givenRefundedQuantities(List.of(new RefundedQuantity(ADULT_ITEM_ID, 2L)));
        givenSaveReturnsWithId();

        // when
        refundService.createAdminFullRefund(PG_ID, "관리자 환불", VISIT_DAY_START);

        // then
        assertThat(captureSavedRefund().getRefundAmount()).isEqualTo(10000);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED", "CANCELLED", "REFUNDED"})
    @DisplayName("관리자 전체 환불도 환불 불가 결제 상태를 거부한다")
    void createAdminFullRefund_InvalidPaymentStatus_RejectsRequest(PaymentStatus status) {
        // given
        givenPayment(createPayment(status));

        // when & then
        assertThatThrownBy(() -> refundService.createAdminFullRefund(PG_ID, "관리자 환불", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT);
        verifyNoInteractions(ticketService, refundRepository);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED", "CANCELLED", "REFUNDED"})
    @DisplayName("관리자 부분 환불도 환불 불가 결제 상태를 거부한다")
    void createAdminPartialRefund_InvalidPaymentStatus_RejectsRequest(PaymentStatus status) {
        // given
        givenPayment(createPayment(status));

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, List.of(1L), "관리자 환불", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_NOT_REFUNDABLE_PAYMENT);
        verifyNoInteractions(ticketService, refundRepository);
    }

    @ParameterizedTest
    @EnumSource(value = TicketStatus.class, names = {"IN_USE", "USED", "EXPIRED", "REFUNDED"})
    @DisplayName("관리자 부분 환불도 사용 중·사용 완료·만료·환불된 티켓을 거부한다")
    void createAdminPartialRefund_NotUnusedTicket_RejectsRequest(TicketStatus status) {
        // given
        givenPayment(payment);
        List<Ticket> selected = tickets(adultItem(payment), 1L, 1, status);
        givenRefundTickets(selected);
        givenRefundedQuantities(List.of());

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, List.of(1L), "관리자 환불", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TICKET_NOT_REFUNDABLE);
        verify(refundRepository, never()).save(any());
        assertThat(selected.get(0).getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("관리자 부분 환불은 티켓 목록이 없으면 결제 조회 전에 거부한다")
    void createAdminPartialRefund_EmptyTicketIds_RejectsRequest(List<Long> ids) {
        // given
        String reason = "관리자 환불";

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, ids, reason, NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        verifyNoInteractions(paymentRepository, ticketService, refundRepository);
    }

    @Test
    @DisplayName("관리자 부분 환불은 중복 티켓 ID를 결제 조회 전에 거부한다")
    void createAdminPartialRefund_DuplicateTicketIds_RejectsRequest() {
        // given
        List<Long> ids = List.of(1L, 1L);

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, ids, "관리자 환불", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_DUPLICATE_TICKET);
        verifyNoInteractions(paymentRepository, ticketService, refundRepository);
    }

    @Test
    @DisplayName("관리자 부분 환불도 대상 주문에 속하지 않는 티켓을 거부한다")
    void createAdminPartialRefund_TicketOutsideOrder_RejectsRequest() {
        // given
        givenPayment(payment);
        given(ticketService.findRefundTicketsForUpdate(anySet(), anySet()))
                .willThrow(new BusinessException(ErrorCode.REFUND_INVALID_TICKET));

        // when & then
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, List.of(99L), "관리자 환불", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_INVALID_TICKET);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("관리자 전체 환불은 미사용 티켓이 없으면 거부한다")
    void createAdminFullRefund_NoUnusedTickets_RejectsRequest() {
        // given
        givenPayment(payment);
        given(ticketService.findUnusedTicketsForUpdate(anySet())).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> refundService.createAdminFullRefund(PG_ID, "관리자 환불", VISIT_DAY_START))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFUND_INVALID_QUANTITY);
        verify(refundRepository, never()).save(any());
    }

    @Test
    @DisplayName("관리자 전체·부분 환불 모두 존재하지 않는 결제를 거부한다")
    void createAdminRefunds_PaymentNotFound_RejectsRequests() {
        // given
        given(paymentRepository.findUserIdByPgId(PG_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> refundService.createAdminFullRefund(PG_ID, "관리자 환불", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        assertThatThrownBy(() -> refundService.createAdminPartialRefund(PG_ID, List.of(1L), "관리자 환불", NOW))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        verifyNoInteractions(ticketService, refundRepository);
    }

    private void givenPayment(Payment found) {
        // 소유자 ID 조회 → 회원 잠금 → 결제 잠금의 실제 서비스 흐름에 맞춘다.
        User owner = found.getOrder().getUser();
        given(paymentRepository.findUserIdByPgId(PG_ID)).willReturn(Optional.of(owner.getId()));
        given(userRepository.findByIdForUpdate(owner.getId())).willReturn(Optional.of(owner));
        given(paymentRepository.findWithLockByPgId(PG_ID)).willReturn(Optional.of(found));
    }

    private void givenRefundTickets(List<Ticket> tickets) {
        given(ticketService.findRefundTicketsForUpdate(anySet(), anySet())).willReturn(tickets);
    }

    private void givenRefundedQuantities(List<RefundedQuantity> refundedQuantities) {
        given(refundItemRepository.sumQuantityByOrderItemIdInAndStatusIn(anySet(), anyList()))
                .willReturn(refundedQuantities);
    }

    private void givenSaveReturnsWithId() {
        given(refundRepository.save(any(Refund.class))).willAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            ReflectionTestUtils.setField(refund, "id", 1L);
            return refund;
        });
    }

    private Refund captureSavedRefund() {
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundRepository).save(captor.capture());
        return captor.getValue();
    }

    // 성인 10,000원 × 3장, 어린이 5,000원 × 2장 주문의 결제
    private Payment createPayment(PaymentStatus status) {
        User user = User.builder()
                .email("test@email.com")
                .password("password")
                .nickname("name")
                .build();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        Order order = Order.create(user, 40000, VISIT_DATE);
        ReflectionTestUtils.setField(order, "id", 10L);
        addOrderItem(order, ADULT_ITEM_ID, 10000, 3);
        addOrderItem(order, CHILD_ITEM_ID, 5000, 2);

        Payment created = Payment.create(order);
        switch (status) {
            case PENDING -> {
            }
            case PAID -> created.markAsPaid(NOW);
            case PARTIALLY_REFUNDED -> {
                created.markAsPaid(NOW);
                created.markAsPartiallyRefunded();
            }
            case REFUNDED -> {
                created.markAsPaid(NOW);
                created.markAsRefunded();
            }
            case FAILED -> created.markAsFailed();
            case CANCELLED -> created.markAsCancelled();
        }
        return created;
    }

    private void addOrderItem(Order order, Long id, int price, int quantity) {
        OrderItem orderItem = BeanUtils.instantiateClass(OrderItem.class);
        ReflectionTestUtils.setField(orderItem, "id", id);
        ReflectionTestUtils.setField(orderItem, "order", order);
        ReflectionTestUtils.setField(orderItem, "price", price);
        ReflectionTestUtils.setField(orderItem, "quantity", quantity);
        order.getOrderItems().add(orderItem);
    }

    private OrderItem adultItem(Payment target) {
        return target.getOrder().getOrderItems().get(0);
    }

    private OrderItem childItem(Payment target) {
        return target.getOrder().getOrderItems().get(1);
    }

    // 주문 상품의 티켓을 firstId부터 count장 만든다.
    private List<Ticket> tickets(OrderItem orderItem, Long firstId, int count, TicketStatus status) {
        List<Ticket> tickets = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Ticket ticket = BeanUtils.instantiateClass(Ticket.class);
            ReflectionTestUtils.setField(ticket, "id", firstId + index);
            ReflectionTestUtils.setField(ticket, "orderItem", orderItem);
            ReflectionTestUtils.setField(ticket, "status", status);
            tickets.add(ticket);
        }
        return tickets;
    }

    private List<Long> ticketIds(List<Ticket> tickets) {
        return tickets.stream()
                .map(Ticket::getId)
                .toList();
    }
}
