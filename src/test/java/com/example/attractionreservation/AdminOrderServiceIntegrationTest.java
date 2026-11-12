package com.example.attractionreservation;

import com.example.attractionreservation.domain.order.dto.CreateAdminOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.order.service.AdminOrderService;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@Transactional
class AdminOrderServiceIntegrationTest {

    @Autowired
    private AdminOrderService adminOrderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TicketProductRepository ticketProductRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EntityManager entityManager;

    private Long firstUserId;
    private Long firstOrderId;
    private Long secondOrderId;
    private Long thirdOrderId;
    private String firstPgId;
    private Long productId;

    @BeforeEach
    void prepareOrders() {
        User firstUser = saveUser("첫번째회원");
        User secondUser = saveUser("두번째회원");
        firstUserId = firstUser.getId();

        TicketProduct product = ticketProductRepository.saveAndFlush(
                new TicketProduct(
                        "관리자조회상품",
                        10000,
                        "관리자 주문 조회 검증",
                        TicketType.ALL_DAY,
                        100,
                        TicketProductStatus.ON_SALE
                )
        );

        Order first = saveOrder(firstUser, product, OrderStatus.PENDING);
        Order second = saveOrder(firstUser, product, OrderStatus.COMPLETED);
        Order third = saveOrder(secondUser, product, OrderStatus.PENDING);

        firstOrderId = first.getId();
        secondOrderId = second.getId();
        thirdOrderId = third.getId();
        productId = product.getId();

        Payment payment = paymentRepository.saveAndFlush(
                Payment.create(first)
        );
        firstPgId = payment.getPgId();

        // 생성 시각이 같아도 주문 ID 내림차순으로 정렬되는지 확인한다.
        entityManager.createQuery("""
                UPDATE Order o
                SET o.createdAt = :createdAt
                WHERE o.id IN :ids
                """)
                .setParameter(
                        "createdAt",
                        Instant.parse("2026-11-01T01:00:00Z")
                )
                .setParameter(
                        "ids",
                        List.of(firstOrderId, secondOrderId, thirdOrderId)
                )
                .executeUpdate();

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("여러 회원의 주문을 ID 내림차순으로 페이지 조회한다")
    void findOrders_allMembers_returnsStablePages() {
        var firstPage = adminOrderService.findOrders(
                new SearchAdminOrderCondition(0, 2, null, null)
        );
        var secondPage = adminOrderService.findOrders(
                new SearchAdminOrderCondition(1, 2, null, null)
        );

        assertThat(firstPage.totalElements()).isEqualTo(3L);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.content())
                .extracting(item -> item.orderId())
                .containsExactly(thirdOrderId, secondOrderId);

        assertThat(secondPage.page()).isEqualTo(1);
        assertThat(secondPage.content())
                .extracting(item -> item.orderId())
                .containsExactly(firstOrderId);
    }

    @Test
    @DisplayName("회원 필터와 주문 상태 필터를 각각 또는 함께 적용한다")
    void findOrders_filters_returnsMatchingOrders() {
        var memberOrders = adminOrderService.findOrders(
                new SearchAdminOrderCondition(
                        0, 10, firstUserId, null
                )
        );
        assertThat(memberOrders.totalElements()).isEqualTo(2L);
        assertThat(memberOrders.content())
                .extracting(item -> item.orderId())
                .containsExactly(secondOrderId, firstOrderId);

        var pendingOrders = adminOrderService.findOrders(
                new SearchAdminOrderCondition(
                        0, 10, null, OrderStatus.PENDING
                )
        );
        assertThat(pendingOrders.totalElements()).isEqualTo(2L);
        assertThat(pendingOrders.content())
                .extracting(item -> item.orderId())
                .containsExactly(thirdOrderId, firstOrderId);

        var memberPendingOrders = adminOrderService.findOrders(
                new SearchAdminOrderCondition(
                        0, 10, firstUserId, OrderStatus.PENDING
                )
        );
        assertThat(memberPendingOrders.totalElements()).isEqualTo(1L);
        assertThat(memberPendingOrders.content())
                .extracting(item -> item.orderId())
                .containsExactly(firstOrderId);
    }

    @Test
    @DisplayName("관리자 상세 조회에서 실제 주문자와 항목 및 결제번호를 반환한다")
    void findOrderById_savedOrder_returnsDetail() {
        var response = adminOrderService.findOrderById(firstOrderId);

        assertThat(response.orderId()).isEqualTo(firstOrderId);
        assertThat(response.userId()).isEqualTo(firstUserId);
        assertThat(response.nickname()).isEqualTo("첫번째회원");
        assertThat(response.totalAmount()).isEqualTo(10000);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).quantity()).isEqualTo(1);
        assertThat(response.pgId()).isEqualTo(firstPgId);
        // 결제 전 주문은 아직 발급된 입장권이 없다.
        assertThat(response.tickets()).isEmpty();
    }

    @Test
    @DisplayName("관리자 상세 조회에서 주문에 발급된 입장권 번호와 상태를 ID 순서로 반환한다")
    void findOrderById_issuedTickets_returnsTickets() {
        Order order = orderRepository.findById(secondOrderId).orElseThrow();
        OrderItem orderItem = order.getOrderItems().get(0);

        Ticket unused = ticketRepository.save(new Ticket(order.getVisitDate(), orderItem));
        Ticket refunded = ticketRepository.save(new Ticket(order.getVisitDate(), orderItem));
        refunded.refund();

        entityManager.flush();
        entityManager.clear();

        var response = adminOrderService.findOrderById(secondOrderId);

        assertThat(response.tickets())
                .extracting(
                        SearchAdminOrderResponse.TicketSummary::ticketId,
                        SearchAdminOrderResponse.TicketSummary::orderItemId,
                        SearchAdminOrderResponse.TicketSummary::name,
                        SearchAdminOrderResponse.TicketSummary::status
                )
                .containsExactly(
                        tuple(unused.getId(), orderItem.getId(), "관리자조회상품", "UNUSED"),
                        tuple(refunded.getId(), orderItem.getId(), "관리자조회상품", "REFUNDED")
                );
    }

    @Test
    @DisplayName("결제가 없는 주문도 관리자 상세 조회가 가능하다")
    void findOrderById_withoutPayment_returnsNullPgId() {
        var response = adminOrderService.findOrderById(thirdOrderId);

        assertThat(response.orderId()).isEqualTo(thirdOrderId);
        assertThat(response.pgId()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 주문은 주문 미존재 오류를 반환한다")
    void findOrderById_missingOrder_throwsNotFound() {
        assertThatThrownBy(() ->
                adminOrderService.findOrderById(Long.MAX_VALUE))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_FOUND)
                );
    }

    private User saveUser(String nickname) {
        return userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@example.test")
                        .password("test-password-hash")
                        .nickname(nickname)
                        .build()
        );
    }

    private Order saveOrder(
            User user,
            TicketProduct product,
            OrderStatus status
    ) {
        Order order = Order.create(
                user,
                10000,
                LocalDate.now(KstTime.ZONE).plusDays(1)
        );
        order.addOrderItem(product, 1);
        ReflectionTestUtils.setField(order, "status", status);

        return orderRepository.saveAndFlush(order);
    }

    @Test
    @DisplayName("관리자가 생성한 주문은 대상 회원 소유이며 취소 시 재고와 결제가 복구된다")
    void createAndCancelOrder_admin_preservesOwnershipAndStock() {
        var request = new CreateAdminOrderRequest(
                firstUserId,
                List.of(new CreateOrderRequest.Item(productId, 2)),
                LocalDate.now(KstTime.ZONE).plusDays(1)
        );

        var created = adminOrderService.createOrder(Long.MAX_VALUE, request);

        entityManager.flush();
        entityManager.clear();

        Order savedOrder = orderRepository.findById(created.orderId())
                .orElseThrow();

        assertThat(savedOrder.getUser().getId()).isEqualTo(firstUserId);
        assertThat(savedOrder.getTotalAmount()).isEqualTo(20000);
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(ticketProductRepository.findById(productId)
                .orElseThrow().getStock()).isEqualTo(98);
        assertThat(paymentRepository.findByPgId(created.pgId())
                .orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);

        var cancelled = adminOrderService.cancelOrder(
                Long.MAX_VALUE, created.orderId()
        );

        entityManager.flush();
        entityManager.clear();

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(created.orderId())
                .orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findByPgId(created.pgId())
                .orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(ticketProductRepository.findById(productId)
                .orElseThrow().getStock()).isEqualTo(100);

        // 재취소가 거절되고 재고가 한 번만 복구되는지 확인한다.
        assertThatThrownBy(() ->
                adminOrderService.cancelOrder(
                        Long.MAX_VALUE, created.orderId()
                ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );

        assertThat(ticketProductRepository.findById(productId)
                .orElseThrow().getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("관리자도 결제 완료 주문을 일반 취소할 수 없다")
    void cancelOrder_completedOrder_throwsNotCancellable() {
        assertThatThrownBy(() ->
                adminOrderService.cancelOrder(Long.MAX_VALUE, secondOrderId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );
    }

    @Test
    @DisplayName("이용 정지 회원을 대상으로 관리자 주문을 생성할 수 없다")
    void createOrder_suspendedMember_throwsUserNotActive() {
        User target = userRepository.findById(firstUserId).orElseThrow();
        ReflectionTestUtils.setField(target, "status", UserStatus.SUSPENDED);
        entityManager.flush();
        entityManager.clear();

        var request = new CreateAdminOrderRequest(
                firstUserId,
                List.of(new CreateOrderRequest.Item(productId, 1)),
                LocalDate.now(KstTime.ZONE).plusDays(1)
        );

        assertThatThrownBy(() ->
                adminOrderService.createOrder(Long.MAX_VALUE, request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.USER_NOT_ACTIVE)
                );
    }
}