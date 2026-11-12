package com.example.attractionreservation.domain.order.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.service.AdminOrderService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.example.attractionreservation.domain.order.dto.CreateAdminOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@WebMvcTest(AdminOrderController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class AdminOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminOrderService adminOrderService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("관리자는 기본 페이지 조건으로 전체 주문 목록을 조회한다")
    void getOrders_admin_returnsDefaultPage() throws Exception {
        var condition = new SearchAdminOrderCondition(
                null, null, null, null
        );

        when(adminOrderService.findOrders(condition))
                .thenReturn(new SearchAdminOrderListResponse(
                        List.of(), 0, 10, 0L, 0
                ));

        mockMvc.perform(get("/api/admin/orders")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        verify(adminOrderService).findOrders(condition);
    }

    @Test
    @DisplayName("회원 ID와 주문 상태 및 페이지 조건을 서비스에 전달한다")
    void getOrders_filters_passesConditions() throws Exception {
        var condition = new SearchAdminOrderCondition(
                1, 5, 7L, OrderStatus.PENDING
        );

        when(adminOrderService.findOrders(condition))
                .thenReturn(new SearchAdminOrderListResponse(
                        List.of(), 1, 5, 0L, 0
                ));

        mockMvc.perform(get("/api/admin/orders")
                        .with(authenticatedAs("ADMIN"))
                        .param("page", "1")
                        .param("size", "5")
                        .param("userId", "7")
                        .param("status", "PENDING"))
                .andExpect(status().isOk());

        verify(adminOrderService).findOrders(condition);
    }

    @Test
    @DisplayName("관리자는 다른 회원의 주문자 정보와 결제번호를 조회한다")
    void getOrderById_admin_returnsDetail() throws Exception {
        var response = new SearchAdminOrderResponse(
                101L,
                7L,
                "member@example.test",
                "주문회원",
                10000,
                "PENDING",
                LocalDate.of(2026, 11, 5),
                LocalDateTime.of(2026, 11, 4, 10, 0),
                List.of(new SearchOrderResponse.Item(
                        201L, 301L, 10000, 1
                )),
                List.of(new SearchAdminOrderResponse.TicketSummary(
                        401L, 201L, "종일권", "UNUSED", LocalDate.of(2026, 11, 5)
                )),
                "test-payment-101"
        );

        when(adminOrderService.findOrderById(101L))
                .thenReturn(response);

        mockMvc.perform(get("/api/admin/orders/101")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.email")
                        .value("member@example.test"))
                .andExpect(jsonPath("$.data.nickname").value("주문회원"))
                .andExpect(jsonPath("$.data.visitDate")
                        .value("2026-11-05"))
                .andExpect(jsonPath("$.data.pgId")
                        .value("test-payment-101"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.tickets.length()").value(1))
                .andExpect(jsonPath("$.data.tickets[0].ticketId").value(401))
                .andExpect(jsonPath("$.data.tickets[0].orderItemId").value(201))
                .andExpect(jsonPath("$.data.tickets[0].name").value("종일권"))
                .andExpect(jsonPath("$.data.tickets[0].status").value("UNUSED"))
                .andExpect(jsonPath("$.data.tickets[0].validDate").value("2026-11-05"))
                .andExpect(jsonPath("$.data.password").doesNotExist());

        verify(adminOrderService).findOrderById(101L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/orders",
            "/api/admin/orders/101"
    })
    @DisplayName("미인증 요청은 관리자 주문 조회에 접근할 수 없다")
    void getOrders_unauthenticated_returns401(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminOrderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/orders",
            "/api/admin/orders/101"
    })
    @DisplayName("일반 회원은 관리자 주문 조회에 접근할 수 없다")
    void getOrders_regularUser_returns403(String path) throws Exception {
        mockMvc.perform(get(path)
                        .with(authenticatedAs("USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminOrderService);
    }

    @ParameterizedTest
    @CsvSource({
            "page, -1",
            "size, 0",
            "size, 101",
            "userId, 0",
            "status, UNKNOWN",
            "page, abc"
    })
    @DisplayName("잘못된 조회 조건은 HTTP 400으로 거절한다")
    void getOrders_invalidCondition_returns400(
            String name,
            String value
    ) throws Exception {
        mockMvc.perform(get("/api/admin/orders")
                        .with(authenticatedAs("ADMIN"))
                        .param(name, value))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminOrderService);
    }

    @Test
    @DisplayName("존재하지 않는 주문의 상세 조회는 HTTP 404를 반환한다")
    void getOrderById_missingOrder_returns404() throws Exception {
        when(adminOrderService.findOrderById(999L))
                .thenThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        mockMvc.perform(get("/api/admin/orders/999")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isNotFound());
    }

    private RequestPostProcessor authenticatedAs(String role) {
        return authentication(
                new UsernamePasswordAuthenticationToken(
                        99L,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
        );
    }

    @Test
    @DisplayName("관리자는 지정한 회원의 주문을 생성하고 HTTP 201을 받는다")
    void createOrder_admin_returns201() throws Exception {
        var request = new CreateAdminOrderRequest(
                7L,
                List.of(new CreateOrderRequest.Item(301L, 1)),
                LocalDate.of(2026, 11, 5)
        );

        var response = new CreateOrderResponse(
                101L,
                10000,
                "PENDING",
                "test-payment-101",
                List.of(new CreateOrderResponse.Item(
                        201L, 301L, 10000, 1
                ))
        );

        when(adminOrderService.createOrder(99L, request))
                .thenReturn(response);

        mockMvc.perform(post("/api/admin/orders")
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody(7, 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.pgId")
                        .value("test-payment-101"));

        verify(adminOrderService).createOrder(99L, request);
    }

    @Test
    @DisplayName("관리자는 지정한 주문을 취소한다")
    void cancelOrder_admin_returns200() throws Exception {
        when(adminOrderService.cancelOrder(99L, 101L))
                .thenReturn(UpdateOrderResponse.of(
                        101L, OrderStatus.CANCELLED
                ));

        mockMvc.perform(post("/api/admin/orders/101/cancel")
                        .with(authenticatedAs("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(101))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        verify(adminOrderService).cancelOrder(99L, 101L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/orders",
            "/api/admin/orders/101/cancel"
    })
    @DisplayName("미인증 요청은 관리자 주문 생성과 취소를 할 수 없다")
    void writeOrder_unauthenticated_returns401(String path) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody(7, 1)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(adminOrderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/orders",
            "/api/admin/orders/101/cancel"
    })
    @DisplayName("일반 회원은 관리자 주문 생성과 취소를 할 수 없다")
    void writeOrder_regularUser_returns403(String path) throws Exception {
        mockMvc.perform(post(path)
                        .with(authenticatedAs("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody(7, 1)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminOrderService);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 1",
            "-1, 1",
            "7, 0",
            "7, -1"
    })
    @DisplayName("잘못된 대상 회원 ID 또는 수량은 거절한다")
    void createOrder_invalidInput_returns400(
            long userId,
            int quantity
    ) throws Exception {
        mockMvc.perform(post("/api/admin/orders")
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adminCreateBody(userId, quantity)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminOrderService);
    }

    @Test
    @DisplayName("주문 항목이 null이면 거절한다")
    void createOrder_nullItem_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/orders")
                        .with(authenticatedAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "userId": 7,
                              "items": [null],
                              "visitDate": "2026-11-05"
                            }
                            """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminOrderService);
    }

    private String adminCreateBody(long userId, int quantity) {
        return """
            {
              "userId": %d,
              "items": [
                {
                  "ticketProductId": 301,
                  "quantity": %d
                }
              ],
              "visitDate": "2026-11-05"
            }
            """.formatted(userId, quantity);
    }
}