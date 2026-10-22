package com.example.attractionreservation.domain.order.service;

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
}