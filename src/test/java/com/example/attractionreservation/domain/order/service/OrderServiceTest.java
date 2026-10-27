package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.order.dto.SearchOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private TicketProductRepository ticketProductRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private OrderService orderService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .email("order@example.com")
                .password("encoded-password")
                .nickname("주문회원")
                .build();
    }

    @Test
    @DisplayName("서버 가격으로 총금액을 계산하고 주문 당시 단가를 저장한다")
    void createOrder_validItems_savesPendingOrder() {
        // given
        TicketProduct firstProduct =
                createProduct(10L, 60000, TicketProductStatus.ON_SALE);
        TicketProduct secondProduct =
                createProduct(20L, 30000, TicketProductStatus.ON_SALE);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L, 20L)))
                .thenReturn(List.of(secondProduct, firstProduct));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    ReflectionTestUtils.setField(order, "id", 101L);
                    return order;
                });
        when(paymentService.createPayment(any(Order.class))).thenReturn("pg-id");

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(10L, 2),
                new CreateOrderRequest.Item(20L, 1)
        ));

        // when
        CreateOrderResponse response = orderService.createOrder(1L, request);

        // then
        verify(ticketProductRepository).findAllById(Set.of(10L, 20L));
        verifyNoMoreInteractions(ticketProductRepository);
        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.totalAmount()).isEqualTo(150000);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.pgId()).isEqualTo("pg-id");
        assertThat(response.items())
                .extracting(CreateOrderResponse.Item::price)
                .containsExactly(60000, 30000);
        assertThat(response.items())
                .extracting(CreateOrderResponse.Item::quantity)
                .containsExactly(2, 1);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        Order savedOrder = captor.getValue();

        assertThat(savedOrder.getUser()).isSameAs(user);
        // 결제창 연동에 쓸 결제를 저장한 주문으로 함께 만든다.
        verify(paymentService).createPayment(savedOrder);
        assertThat(savedOrder.getOrderItems()).allSatisfy(item ->
                assertThat(item.getOrder()).isSameAs(savedOrder));

        // 상품 가격이 바뀌어도 이미 저장한 주문 단가는 유지된다.
        ReflectionTestUtils.setField(firstProduct, "price", 70000);
        assertThat(savedOrder.getOrderItems().get(0).getPrice())
                .isEqualTo(60000);

        assertThat(firstProduct.getStock()).isEqualTo(100);
        assertThat(secondProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("결제 생성에 실패하면 주문 생성도 같은 예외로 실패한다")
    void createOrder_paymentCreationFailed_throwsException() {
        // given
        TicketProduct product = createProduct(10L, 60000, TicketProductStatus.ON_SALE);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L))).thenReturn(List.of(product));
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentService.createPayment(any(Order.class)))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_INVALID_AMOUNT));

        // when & then: 예외가 전파되어야 같은 트랜잭션의 주문 저장도 롤백된다.
        assertThatThrownBy(() -> orderService.createOrder(1L, createRequest(10L, 1)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.PAYMENT_INVALID_AMOUNT)
                );
    }

    @Test
    @DisplayName("회원이 존재하지 않으면 주문을 저장하지 않는다")
    void createOrder_missingUser_throwsUnauthorized() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        // when & then
        assertOrderRejected(
                createRequest(10L, 1),
                ErrorCode.UNAUTHORIZED
        );
        verifyNoInteractions(ticketProductRepository);
    }

    @Test
    @DisplayName("상품이 존재하지 않으면 주문을 저장하지 않는다")
    void createOrder_missingProduct_throwsNotFound() {
        // given: 회원은 존재하지만 요청한 상품은 없다.
        when(userRepository.findById(1L))
                .thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of());

        // when & then
        assertOrderRejected(
                createRequest(10L, 1),
                ErrorCode.TICKET_PRODUCT_NOT_FOUND
        );
    }

    @Test
    @DisplayName("판매 중지 상품은 주문할 수 없다")
    void createOrder_stoppedProduct_throwsConflict() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of(
                        createProduct(10L, 60000, TicketProductStatus.STOPPED)
                ));

        // when & then
        assertOrderRejected(
                createRequest(10L, 1),
                ErrorCode.ORDER_PRODUCT_NOT_ON_SALE
        );
    }

    @Test
    @DisplayName("단가와 수량의 곱이 정수 범위를 넘으면 주문을 저장하지 않는다")
    void createOrder_itemAmountOverflow_throwsBadRequest() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of(
                        createProduct(
                                10L,
                                Integer.MAX_VALUE,
                                TicketProductStatus.ON_SALE
                        )
                ));

        // when & then
        assertOrderRejected(
                createRequest(10L, 2),
                ErrorCode.ORDER_AMOUNT_OUT_OF_RANGE
        );
    }

    @Test
    @DisplayName("각 항목 금액이 유효해도 합계가 정수 범위를 넘으면 거부한다")
    void createOrder_totalAmountOverflow_throwsBadRequest() {
        // given
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(ticketProductRepository.findAllById(Set.of(10L)))
                .thenReturn(List.of(
                        createProduct(
                                10L,
                                Integer.MAX_VALUE,
                                TicketProductStatus.ON_SALE
                        )
                ));

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(10L, 1),
                new CreateOrderRequest.Item(10L, 1)
        ));

        // when & then
        assertOrderRejected(request, ErrorCode.ORDER_AMOUNT_OUT_OF_RANGE);
        verify(ticketProductRepository).findAllById(Set.of(10L));
        verifyNoMoreInteractions(ticketProductRepository);
    }

    private void assertOrderRejected(
            CreateOrderRequest request,
            ErrorCode expectedError
    ) {
        assertThatThrownBy(() -> orderService.createOrder(1L, request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(expectedError)
                );

        verifyNoInteractions(orderRepository);
    }

    private CreateOrderRequest createRequest(Long productId, int quantity) {
        return new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(productId, quantity)
        ));
    }

    private TicketProduct createProduct(
            Long productId,
            int price,
            TicketProductStatus status
    ) {
        TicketProduct product = new TicketProduct(
                "테스트상품", price, "테스트설명",
                TicketType.ALL_DAY, 100, status
        );
        ReflectionTestUtils.setField(product, "id", productId);
        return product;
    }

    @Test
    @DisplayName("본인의 주문 상세와 주문 당시 단가 및 KST 생성 시각을 반환한다")
    void findOrderById_ownOrder_returnsDetail() {
        // given
        ReflectionTestUtils.setField(user, "id", 1L);

        TicketProduct product =
                createProduct(10L, 60000, TicketProductStatus.ON_SALE);

        Order order = Order.create(user, 120000);
        order.addOrderItem(product, 2);

        ReflectionTestUtils.setField(order, "id", 101L);
        ReflectionTestUtils.setField(
                order, "createdAt", Instant.parse("2026-10-14T01:00:00Z")
        );
        ReflectionTestUtils.setField(
                order.getOrderItems().get(0), "id", 201L
        );

        // 현재 상품 가격과 주문 당시 단가가 다르도록 설정한다.
        ReflectionTestUtils.setField(product, "price", 70000);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // when
        SearchOrderResponse response = orderService.findOrderById(1L, 101L);

        // then
        assertThat(response.orderId()).isEqualTo(101L);
        assertThat(response.totalAmount()).isEqualTo(120000);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.createdAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 10, 0));
        assertThat(response.items()).containsExactly(
                new SearchOrderResponse.Item(201L, 10L, 60000, 2)
        );

        verify(orderRepository).findOrderById(101L);
        verifyNoMoreInteractions(orderRepository);
        verifyNoInteractions(ticketProductRepository, userRepository);
    }

    @Test
    @DisplayName("존재하지 않는 주문을 조회하면 주문 미존재 오류를 반환한다")
    void findOrderById_missingOrder_throwsNotFound() {
        // given
        when(orderRepository.findOrderById(999L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> orderService.findOrderById(1L, 999L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );

        verify(orderRepository).findOrderById(999L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("다른 회원의 주문을 조회하면 접근을 거부한다")
    void findOrderById_otherMemberOrder_throwsForbidden() {
        // given: 주문 소유자는 2번 회원이며 요청자는 1번 회원이다.
        ReflectionTestUtils.setField(user, "id", 2L);
        Order order = Order.create(user, 120000);

        when(orderRepository.findOrderById(101L))
                .thenReturn(Optional.of(order));

        // when & then
        assertThatThrownBy(() -> orderService.findOrderById(1L, 101L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_ACCESS_DENIED)
                );

        verify(orderRepository).findOrderById(101L);
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("본인 ID와 최신순 페이지 조건으로 조회하고 목록 응답을 반환한다")
    void findOrdersByUserId_validCondition_returnsPage() {
        // given
        Order order = Order.create(user, 120000);
        ReflectionTestUtils.setField(order, "id", 101L);
        ReflectionTestUtils.setField(
                order, "createdAt", Instant.parse("2026-10-14T01:00:00Z")
        );

        when(orderRepository.findAllByUser_Id(eq(1L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return new PageImpl<>(List.of(order), pageable, 3);
                });

        // when
        SearchOrderListResponse response = orderService.findOrdersByUserId(
                1L,
                new SearchOrderCondition(1, 1)
        );

        // then
        assertThat(response.content()).containsExactly(
                new SearchOrderListResponse.Item(
                        101L,
                        120000,
                        "PENDING",
                        LocalDateTime.of(2026, 10, 14, 10, 0)
                )
        );
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(3L);
        assertThat(response.totalPages()).isEqualTo(3);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAllByUser_Id(eq(1L), captor.capture());

        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(1);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        ));
        verifyNoMoreInteractions(orderRepository);
    }

    @Test
    @DisplayName("본인 주문이 없으면 빈 목록과 전체 개수 0을 반환한다")
    void findOrdersByUserId_noOrders_returnsEmptyPage() {
        // given
        when(orderRepository.findAllByUser_Id(eq(1L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return Page.<Order>empty(pageable);
                });

        // when
        SearchOrderListResponse response = orderService.findOrdersByUserId(
                1L,
                new SearchOrderCondition(null, null)
        );

        // then
        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();

        verify(orderRepository).findAllByUser_Id(eq(1L), any(Pageable.class));
        verifyNoMoreInteractions(orderRepository);
    }
}