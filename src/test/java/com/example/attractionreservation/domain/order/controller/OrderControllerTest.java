package com.example.attractionreservation.domain.order.controller;

import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.dto.SearchOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(OrderController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("인증된 회원의 주문 생성 결과를 HTTP 201로 반환한다")
    void createOrder_validRequest_returnsCreated() throws Exception {
        // given
        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(1L, 2)
        ));
        CreateOrderResponse response = new CreateOrderResponse(
                101L,
                120000,
                "PENDING",
                List.of(new CreateOrderResponse.Item(201L, 1L, 60000, 2))
        );

        when(orderService.createOrder(1L, request)).thenReturn(response);

        // when & then: 요청에 다른 회원 ID가 있어도 인증된 ID를 사용한다.
        mockMvc.perform(post("/api/orders")
                        .with(authenticatedMember())
                        .param("userId", "999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("주문 생성에 성공했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.totalAmount").value(120000))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(201))
                .andExpect(jsonPath("$.data.items[0].ticketProductId").value(1))
                .andExpect(jsonPath("$.data.items[0].price").value(60000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        verify(orderService).createOrder(1L, request);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    @DisplayName("인증 없이 주문하면 HTTP 401을 반환한다")
    void createOrder_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보 없이 유효한 본문을 전달한다.

        // when & then
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @DisplayName("주문 항목이나 상품 ID 또는 수량이 유효하지 않으면 HTTP 400을 반환한다")
    @ValueSource(strings = {
            "{}",
            "{\"items\":[]}",
            "{\"items\":[null]}",
            "{\"items\":[{\"quantity\":1}]}",
            "{\"items\":[{\"ticketProductId\":0,\"quantity\":1}]}",
            "{\"items\":[{\"ticketProductId\":1}]}",
            "{\"items\":[{\"ticketProductId\":1,\"quantity\":0}]}",
            "{\"items\":[{\"ticketProductId\":1,\"quantity\":-1}]}"
    })
    void createOrder_invalidInput_returnsBadRequest(String body)
            throws Exception {
        // given: 잘못된 입력값을 전달한다.

        // when & then
        mockMvc.perform(post("/api/orders")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("존재하지 않는 상품의 주문은 HTTP 404를 반환한다")
    void createOrder_missingProduct_returnsNotFound() throws Exception {
        // given
        when(orderService.createOrder(eq(1L), any(CreateOrderRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/orders")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.TICKET_PRODUCT_NOT_FOUND.getMessage()));
    }

    @Test
    @DisplayName("판매 불가 상품의 주문은 HTTP 409를 반환한다")
    void createOrder_unavailableProduct_returnsConflict() throws Exception {
        // given
        when(orderService.createOrder(eq(1L), any(CreateOrderRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.ORDER_PRODUCT_NOT_ON_SALE));

        // when & then
        mockMvc.perform(post("/api/orders")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(ErrorCode.ORDER_PRODUCT_NOT_ON_SALE.getMessage()));
    }

    @Test
    @DisplayName("인증된 회원의 주문을 취소하고 HTTP 200을 반환한다")
    void updateOrderByCancel_ownedOrder_returnsOk() throws Exception {
        // given
        when(orderService.updateOrderByCancel(1L, 101L))
                .thenReturn(UpdateOrderResponse.of(
                        101L,
                        OrderStatus.CANCELLED
                ));

        // when & then: 별도 회원 ID가 전달돼도 인증된 회원 ID를 사용한다.
        mockMvc.perform(post("/api/orders/{orderId}/cancel", 101L)
                        .with(authenticatedMember())
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("주문 취소에 성공했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(orderService).updateOrderByCancel(1L, 101L);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    @DisplayName("인증 없이 주문을 취소하면 HTTP 401을 반환한다")
    void updateOrderByCancel_unauthenticated_returnsUnauthorized()
            throws Exception {
        // given: 인증 정보를 전달하지 않는다.

        // when & then
        mockMvc.perform(post("/api/orders/{orderId}/cancel", 101L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {
                    "ORDER_NOT_FOUND",
                    "ORDER_CANCEL_ACCESS_DENIED",
                    "ORDER_NOT_CANCELLABLE"
            }
    )
    @DisplayName("주문 취소 예외를 해당 HTTP 상태와 공통 오류 응답으로 반환한다")
    void updateOrderByCancel_rejected_returnsExpectedError(
            ErrorCode errorCode
    ) throws Exception {
        // given
        when(orderService.updateOrderByCancel(1L, 101L))
                .thenThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(post("/api/orders/{orderId}/cancel", 101L)
                        .with(authenticatedMember()))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value(errorCode.getMessage()));

        verify(orderService).updateOrderByCancel(1L, 101L);
    }

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }

    private String validRequestBody() {
        return """
                {
                    "items": [
                        {
                            "ticketProductId": 1,
                            "quantity": 2
                        }
                    ]
                }
                """;
    }

    @Test
    @DisplayName("본인 주문 상세를 HTTP 200과 명세서의 응답 항목으로 반환한다")
    void getOrderById_ownOrder_returnsOk() throws Exception {
        // given
        SearchOrderResponse response = new SearchOrderResponse(
                101L,
                120000,
                "PENDING",
                LocalDateTime.of(2026, 10, 14, 10, 0),
                List.of(
                        new SearchOrderResponse.Item(201L, 1L, 60000, 2)
                )
        );

        when(orderService.findOrderById(1L, 101L))
                .thenReturn(response);

        // when & then: 요청값으로 다른 회원 ID를 보내도 인증된 ID를 사용한다.
        mockMvc.perform(get("/api/orders/101")
                        .with(authenticatedMember())
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("주문 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.totalAmount").value(120000))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.createdAt")
                        .value("2026-10-14T10:00:00"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(201))
                .andExpect(jsonPath("$.data.items[0].ticketProductId").value(1))
                .andExpect(jsonPath("$.data.items[0].price").value(60000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        verify(orderService).findOrderById(1L, 101L);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    @DisplayName("인증 없이 주문 상세를 조회하면 HTTP 401을 반환한다")
    void getOrderById_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보를 전달하지 않는다.

        // when & then
        mockMvc.perform(get("/api/orders/101"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @EnumSource(
            value = ErrorCode.class,
            names = {"ORDER_NOT_FOUND", "ORDER_ACCESS_DENIED"}
    )
    @DisplayName("주문 미존재와 타인 주문 접근 오류를 각각 HTTP 404와 403으로 반환한다")
    void getOrderById_rejectedOrder_returnsExpectedError(ErrorCode errorCode)
            throws Exception {
        // given
        when(orderService.findOrderById(1L, 101L))
                .thenThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(get("/api/orders/101")
                        .with(authenticatedMember()))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));

        verify(orderService).findOrderById(1L, 101L);
    }

    @Test
    @DisplayName("조회 조건을 생략하면 기본 페이지로 본인 주문 목록을 반환한다")
    void getOrderAll_noCondition_returnsDefaultPage() throws Exception {
        // given
        SearchOrderCondition condition = new SearchOrderCondition(0, 10);
        SearchOrderListResponse response = new SearchOrderListResponse(
                List.of(new SearchOrderListResponse.Item(
                        101L,
                        120000,
                        "PENDING",
                        LocalDateTime.of(2026, 10, 14, 10, 0)
                )),
                0,
                10,
                1L,
                1
        );

        when(orderService.findOrdersByUserId(1L, condition))
                .thenReturn(response);

        // when & then: 요청한 회원 ID가 아닌 인증된 회원 ID를 사용한다.
        mockMvc.perform(get("/api/orders/me")
                        .with(authenticatedMember())
                        .param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message")
                        .value("주문 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(101))
                .andExpect(jsonPath("$.data.content[0].totalAmount").value(120000))
                .andExpect(jsonPath("$.data.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.content[0].createdAt")
                        .value("2026-10-14T10:00:00"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        verify(orderService).findOrdersByUserId(1L, condition);
        verifyNoMoreInteractions(orderService);
    }

    @Test
    @DisplayName("지정한 페이지 조건을 전달하고 빈 주문 목록도 정상 반환한다")
    void getOrderAll_explicitCondition_returnsEmptyPage() throws Exception {
        // given
        SearchOrderCondition condition = new SearchOrderCondition(2, 5);

        when(orderService.findOrdersByUserId(1L, condition))
                .thenReturn(new SearchOrderListResponse(
                        List.of(), 2, 5, 0L, 0
                ));

        // when & then
        mockMvc.perform(get("/api/orders/me")
                        .with(authenticatedMember())
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));

        verify(orderService).findOrdersByUserId(1L, condition);
        verifyNoMoreInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "page=-1",
            "size=0",
            "size=-1",
            "page=abc",
            "size=abc"
    })
    @DisplayName("페이지 조건이 범위나 숫자 형식에 맞지 않으면 HTTP 400을 반환한다")
    void getOrderAll_invalidCondition_returnsBadRequest(String query)
            throws Exception {
        // given
        String[] parameter = query.split("=");

        // when & then
        mockMvc.perform(get("/api/orders/me")
                        .with(authenticatedMember())
                        .param(parameter[0], parameter[1]))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @Test
    @DisplayName("인증 없이 주문 목록을 조회하면 HTTP 401을 반환한다")
    void getOrderAll_unauthenticated_returnsUnauthorized() throws Exception {
        // given: 인증 정보를 전달하지 않는다.

        // when & then
        mockMvc.perform(get("/api/orders/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(orderService);
    }
}
