package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.payment.dto.PaymentSyncResult;
import com.example.attractionreservation.domain.payment.facade.PaymentFacade;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Pageable;

import java.time.temporal.ChronoUnit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import org.mockito.Spy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrderCancelServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TicketProductRepository ticketProductRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentFacade paymentFacade;

    @Mock
    private OrderCancelProcessor orderCancelProcessor;

    @Mock
    private TicketProductService ticketProductService;

    @Spy
    private Clock clock = Clock.systemUTC();

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("본인의 결제 대기 주문을 취소한다")
    void updateOrderByCancel_ownedPendingOrder_success() {
        // given
        Order order = createOrder(1L);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(1);

        // when
        UpdateOrderResponse response =
                orderService.updateOrderByCancel(1L, 101L);

        // then
        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        verify(orderRepository).cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        );
        verifyNoInteractions(ticketProductRepository, userRepository);

        // 주문 취소 시 연결된 결제의 취소도 요청한다.
        verify(paymentService).cancelledByUser(1L, 101L);

        // 주문 취소 시 연결된 결제의 취소도 요청한다.
        verify(paymentService).cancelledByUser(1L, 101L);

        // 직접 취소도 결제 잠금 → 주문 수정 → 결제 취소 순서인지 확인한다.
        var executionOrder = inOrder(paymentService, orderRepository);

        executionOrder.verify(paymentService)
                .lockPaymentByOrderIdIfExists(101L);

        executionOrder.verify(orderRepository).cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        );

        executionOrder.verify(paymentService)
                .cancelledByUser(1L, 101L);
    }

    @Test
    @DisplayName("존재하지 않는 주문은 취소할 수 없다")
    void updateOrderByCancel_missingOrder_throwsNotFound() {
        // given
        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );

        // 조회에서 거부된 경우 수정 쿼리는 실행하지 않는다.
        verify(orderRepository).findOrderById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("다른 회원의 주문은 취소할 수 없다")
    void updateOrderByCancel_otherUserOrder_throwsForbidden() {
        // given
        Order order = createOrder(2L);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_CANCEL_ACCESS_DENIED)
                );

        verify(orderRepository).findOrderById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @ParameterizedTest
    @EnumSource(
            value = OrderStatus.class,
            names = "PENDING",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("결제 대기 상태가 아닌 주문은 취소할 수 없다")
    void updateOrderByCancel_nonPendingOrder_throwsConflict(
            OrderStatus status
    ) {
        // given
        Order order = createOrder(1L);
        ReflectionTestUtils.setField(order, "status", status);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );

        // 이미 취소됐거나 완료된 주문에는 수정 쿼리를 실행하지 않는다.
        verify(orderRepository).findOrderById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("조회 후 다른 요청이 먼저 처리했다면 취소 실패로 응답한다")
    void updateOrderByCancel_updateConditionNoLongerMatches_throwsConflict() {
        // given
        Order order = createOrder(1L);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // 조회 당시에는 PENDING이지만 실제 수정 시에는 조건이 맞지 않는 상황을 재현한다.
        when(orderRepository.cancelPendingOrder(
                eq(101L),
                eq(1L),
                eq(OrderStatus.PENDING),
                eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(0);

        // when & then
        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );
    }

    @Test
    @DisplayName("30분 전을 기준으로 조회하고 주문별 처리 결과를 집계한다")
    void updateExpiredOrdersByCancel_calculatesCutoff_returnsCount() {
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(createOrder(1L)));

        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        Instant beforeCall = Instant.now();

        int count = orderService.updateExpiredOrdersByCancel();

        Instant afterCall = Instant.now();

        ArgumentCaptor<Instant> cutoffCaptor =
                ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> nowCaptor =
                ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(orderRepository).findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                cutoffCaptor.capture(),
                pageableCaptor.capture()
        );

        verify(orderCancelProcessor).cancelExpiredOrder(
                eq(101L), eq(1L), nowCaptor.capture()
        );

        assertThat(count).isEqualTo(1);
        assertThat(nowCaptor.getValue()).isBetween(beforeCall, afterCall);
        assertThat(cutoffCaptor.getValue())
                .isEqualTo(nowCaptor.getValue().minus(30, ChronoUnit.MINUTES));
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("주문을 취소하면 주문 항목의 상품별 수량만큼 재고를 복구한다")
    void updateOrderByCancel_pendingOrder_restoresStock() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);
        addItem(order, 20L, 3);
        stubCancelSuccess(order);

        // when
        orderService.updateOrderByCancel(1L, 101L);

        // then
        verify(ticketProductService)
                .updateTicketProductsStockIncrease(Map.of(10L, 2, 20L, 3));
    }

    @Test
    @DisplayName("같은 상품이 여러 항목이면 수량을 합산해 재고를 복구한다")
    void updateOrderByCancel_sameProductInMultipleItems_restoresSummedStock() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);
        addItem(order, 10L, 3);
        stubCancelSuccess(order);

        // when
        orderService.updateOrderByCancel(1L, 101L);

        // then
        verify(ticketProductService)
                .updateTicketProductsStockIncrease(Map.of(10L, 5));
    }

    @Test
    @DisplayName("재고 복구는 주문 상태 변경 후, 결제 취소 전에 실행한다")
    void updateOrderByCancel_pendingOrder_restoresStockBetweenOrderAndPaymentCancel() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);
        stubCancelSuccess(order);

        // when
        orderService.updateOrderByCancel(1L, 101L);

        // then
        var executionOrder = inOrder(
                paymentService, orderRepository, ticketProductService
        );
        executionOrder.verify(paymentService)
                .lockPaymentByOrderIdIfExists(101L);
        executionOrder.verify(orderRepository).cancelPendingOrder(
                eq(101L), eq(1L),
                eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                any(Instant.class)
        );
        executionOrder.verify(ticketProductService)
                .updateTicketProductsStockIncrease(Map.of(10L, 2));
        executionOrder.verify(paymentService)
                .cancelledByUser(1L, 101L);
    }

    @Test
    @DisplayName("다른 요청이 먼저 취소했다면 재고를 복구하지 않는다")
    void updateOrderByCancel_updateConditionNoLongerMatches_doesNotRestoreStock() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                eq(101L), eq(1L),
                eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(0);

        // when & then
        assertThatThrownBy(() -> orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );
        verifyNoInteractions(ticketProductService);
    }

    @Test
    @DisplayName("결제 대기 상태가 아닌 주문은 재고를 복구하지 않는다")
    void updateOrderByCancel_nonPendingOrder_doesNotRestoreStock() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);
        ReflectionTestUtils.setField(order, "status", OrderStatus.COMPLETED);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(ticketProductService);
    }

    @Test
    @DisplayName("결제 취소에 실패하면 예외를 전파해 재고 복구도 롤백되게 한다")
    void updateOrderByCancel_paymentCancelFails_propagatesException() {
        // given
        Order order = createOrder(1L);
        addItem(order, 10L, 2);
        stubCancelSuccess(order);
        doThrow(new BusinessException(ErrorCode.PAYMENT_CANCEL_PROHIBITED))
                .when(paymentService).cancelledByUser(1L, 101L);

        // when & then
        assertThatThrownBy(() -> orderService.updateOrderByCancel(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PAYMENT_CANCEL_PROHIBITED)
                );
    }

    private void stubCancelSuccess(Order order) {
        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));
        when(orderRepository.cancelPendingOrder(
                eq(101L), eq(1L),
                eq(OrderStatus.PENDING), eq(OrderStatus.CANCELLED),
                any(Instant.class)
        )).thenReturn(1);
    }

    private void addItem(Order order, Long productId, int quantity) {
        TicketProduct product = new TicketProduct(
                "테스트상품", 30000, "테스트설명",
                TicketType.ALL_DAY, 100, TicketProductStatus.ON_SALE
        );
        ReflectionTestUtils.setField(product, "id", productId);
        order.addOrderItem(product, quantity);
    }

    private Order createOrder(Long userId) {
        User user = User.builder()
                .email("cancel@example.com")
                .password("encoded-password")
                .nickname("취소회원")
                .build();

        // DB 저장 없이 소유자 비교를 검증하도록 테스트용 ID를 설정한다.
        ReflectionTestUtils.setField(user, "id", userId);

        Order order = Order.create(user, 60000, LocalDate.of(2026, 11, 1));
        ReflectionTestUtils.setField(order, "id", 101L);
        return order;
    }

    @Test
    @DisplayName("취소하지 않은 주문은 성공 건수에 포함하지 않는다")
    void updateExpiredOrdersByCancel_notCancelled_returnsZero() {
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(createOrder(1L)));

        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenReturn(false);

        assertThat(orderService.updateExpiredOrdersByCancel()).isZero();

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("한 주문에서 업무 오류가 발생해도 다음 주문을 처리한다")
    void updateExpiredOrdersByCancel_firstFails_continuesNextOrder() {
        Order failedOrder = createOrder(1L);
        Order nextOrder = createOrder(1L);
        ReflectionTestUtils.setField(nextOrder, "id", 102L);

        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(failedOrder, nextOrder));

        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenThrow(
                new BusinessException(ErrorCode.PAYMENT_CANCEL_PROHIBITED)
        );

        when(orderCancelProcessor.cancelExpiredOrder(
                eq(102L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        int count = orderService.updateExpiredOrdersByCancel();

        assertThat(count).isEqualTo(1);

        verify(orderCancelProcessor).cancelExpiredOrder(
                eq(102L), eq(1L), any(Instant.class)
        );
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSyncResult.class, names = {"NOT_PAID", "NOT_OUR_PAYMENT"})
    @DisplayName("PG에서 결제되지 않은 만료 주문은 취소한다")
    void updateExpiredOrdersByCancel_unpaidAtPg_cancelled(PaymentSyncResult result) {
        // given
        givenExpiredOrderWithPayment();
        when(paymentFacade.syncPayment("pg-101")).thenReturn(result);
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentSyncResult.class, names = {"NOT_PAID", "NOT_OUR_PAYMENT"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PG에서 결제·실패가 확인된 만료 주문은 결제 처리 경로가 정리하므로 취소하지 않는다")
    void updateExpiredOrdersByCancel_settledAtPg_notCancelled(PaymentSyncResult result) {
        // given
        givenExpiredOrderWithPayment();
        when(paymentFacade.syncPayment("pg-101")).thenReturn(result);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isZero();
        verifyNoInteractions(orderCancelProcessor);
    }

    @Test
    @DisplayName("결제창을 열지 않아 PG에 결제가 없는 만료 주문은 취소한다")
    void updateExpiredOrdersByCancel_noPaymentAtPg_cancelled() {
        // given
        givenExpiredOrderWithPayment();
        when(paymentFacade.syncPayment("pg-101"))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_PG_PAYMENT_NOT_FOUND));
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("한 주문의 PG 조회만 실패하면 그 주문만 취소하지 않고 다음 주문을 처리한다")
    void updateExpiredOrdersByCancel_singlePgLookupFailed_continuesNextOrder() {
        // given
        givenExpiredOrdersWithPayment(101L, 102L);
        when(paymentFacade.syncPayment("pg-101"))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        when(paymentFacade.syncPayment("pg-102")).thenReturn(PaymentSyncResult.NOT_PAID);
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(102L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
        verify(orderCancelProcessor, never()).cancelExpiredOrder(
                eq(101L), any(), any(Instant.class)
        );
    }

    @Test
    @DisplayName("PG 조회가 연속 3번 실패하면 PG 장애로 보고 남은 주문은 다음 주기로 넘긴다")
    void updateExpiredOrdersByCancel_threeConsecutivePgLookupsFailed_stopsCycle() {
        // given
        givenExpiredOrdersWithPayment(101L, 102L, 103L, 104L);
        for (String pgId : List.of("pg-101", "pg-102", "pg-103")) {
            when(paymentFacade.syncPayment(pgId))
                    .thenThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        }

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isZero();
        verify(orderRepository, never()).findPaymentPgIdByOrderId(104L);
        verifyNoInteractions(orderCancelProcessor);
    }

    @Test
    @DisplayName("PG 조회가 중간에 성공하면 연속 실패 횟수를 다시 센다")
    void updateExpiredOrdersByCancel_pgLookupSucceededBetweenFailures_continues() {
        // given
        givenExpiredOrdersWithPayment(101L, 102L, 103L, 104L, 105L);
        for (String pgId : List.of("pg-101", "pg-102", "pg-104", "pg-105")) {
            when(paymentFacade.syncPayment(pgId))
                    .thenThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));
        }
        when(paymentFacade.syncPayment("pg-103")).thenReturn(PaymentSyncResult.NOT_PAID);
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(103L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
        verify(paymentFacade).syncPayment("pg-105");
    }

    @Test
    @DisplayName("PG 조회 실패는 연동 클라이언트가 이미 기록하므로 자동 취소 실패를 ERROR로 다시 남기지 않는다")
    void updateExpiredOrdersByCancel_pgLookupFailed_noErrorLog(CapturedOutput output) {
        // given
        givenExpiredOrderWithPayment();
        when(paymentFacade.syncPayment("pg-101"))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR));

        // when
        orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(output.getOut())
                .doesNotContain("event=ORDER_AUTO_CANCEL_FAILED")
                .contains("event=ORDER_AUTO_CANCEL_PROCESSED")
                .contains("failureCodes=\"PAYMENT_GATEWAY_ERROR\"");
    }

    @Test
    @DisplayName("결제 동기화 중 다른 처리가 결제를 잠그고 있으면 그 주문만 건너뛰고 다음 주문을 처리한다")
    void updateExpiredOrdersByCancel_paymentLocked_skipsAndContinues(CapturedOutput output) {
        // given
        givenExpiredOrdersWithPayment(101L, 102L);
        when(paymentFacade.syncPayment("pg-101"))
                .thenThrow(new PessimisticLockingFailureException("lock wait timeout"));
        when(paymentFacade.syncPayment("pg-102")).thenReturn(PaymentSyncResult.NOT_PAID);
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(102L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
        verify(orderCancelProcessor, never()).cancelExpiredOrder(
                eq(101L), any(), any(Instant.class)
        );
        assertThat(output.getOut())
                .contains("event=ORDER_AUTO_CANCEL_SKIPPED targetUserId=1 orderId=101")
                .doesNotContain("event=ORDER_AUTO_CANCEL_FAILED");
    }

    @Test
    @DisplayName("PG 조회 실패가 아닌 서버 오류는 자동 취소 실패를 ERROR로 남기고 다음 주문을 처리한다")
    void updateExpiredOrdersByCancel_serverErrorOnCancel_errorLoggedAndContinues(CapturedOutput output) {
        // given
        Order failedOrder = createOrder(1L);
        Order nextOrder = createOrder(1L);
        ReflectionTestUtils.setField(nextOrder, "id", 102L);
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(failedOrder, nextOrder));
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenThrow(new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(102L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
        assertThat(output.getOut())
                .contains("event=ORDER_AUTO_CANCEL_FAILED")
                .contains("errorCode=INTERNAL_SERVER_ERROR");
    }

    @Test
    @DisplayName("결제 데이터가 없는 만료 주문은 PG를 조회하지 않고 취소한다")
    void updateExpiredOrdersByCancel_noPaymentRow_cancelledWithoutPgLookup() {
        // given
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(createOrder(1L)));
        when(orderRepository.findPaymentPgIdByOrderId(101L)).thenReturn(Optional.empty());
        when(orderCancelProcessor.cancelExpiredOrder(
                eq(101L), eq(1L), any(Instant.class)
        )).thenReturn(true);

        // when
        int count = orderService.updateExpiredOrdersByCancel();

        // then
        assertThat(count).isEqualTo(1);
        verifyNoInteractions(paymentFacade);
    }

    // 회원 1의 만료 주문들과 각 주문의 결제(pg-{주문 ID})를 준비한다.
    private void givenExpiredOrdersWithPayment(Long... orderIds) {
        List<Order> orders = new ArrayList<>();
        for (Long orderId : orderIds) {
            Order order = createOrder(1L);
            ReflectionTestUtils.setField(order, "id", orderId);
            orders.add(order);
            lenient().when(orderRepository.findPaymentPgIdByOrderId(orderId))
                    .thenReturn(Optional.of("pg-" + orderId));
        }
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(orders);
    }

    private void givenExpiredOrderWithPayment() {
        when(orderRepository.findExpiredPendingOrders(
                eq(OrderStatus.PENDING),
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(createOrder(1L)));
        when(orderRepository.findPaymentPgIdByOrderId(101L)).thenReturn(Optional.of("pg-101"));
    }
}