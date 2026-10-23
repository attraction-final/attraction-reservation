package com.example.attractionreservation;

import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.order.entity.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.order.service.OrderService;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ActiveProfiles("test")
@Transactional
class OrderServiceIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TicketProductRepository ticketProductRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("주문과 항목을 DB에 함께 저장하고 상품 가격 변경 후에도 주문 단가를 유지한다")
    void createOrder_validRequest_persistsOrderAndPriceSnapshot() {
        // given
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("주문테스트")
                        .build()
        );

        TicketProduct product = ticketProductRepository.saveAndFlush(
                new TicketProduct(
                        "종일이용권",
                        60000,
                        "주문 통합 테스트 상품",
                        TicketType.ALL_DAY,
                        100,
                        TicketProductStatus.ON_SALE
                )
        );

        Long userId = user.getId();
        Long productId = product.getId();

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(productId, 2)
        ));

        // when
        CreateOrderResponse response = orderService.createOrder(userId, request);

        // 상품 가격을 변경해 주문 당시 단가와 분리되어 있는지도 확인한다.
        product.updateTicketProduct(
                "종일이용권",
                70000,
                "가격 변경 상품",
                100,
                TicketProductStatus.ON_SALE
        );

        // SQL을 실행하고 캐시를 비워 실제 DB에서 다시 읽는다.
        entityManager.flush();
        entityManager.clear();

        // then
        Order savedOrder = orderRepository.findById(response.orderId())
                .orElseThrow();

        assertThat(response.orderId()).isNotNull();
        assertThat(response.items()).hasSize(1);

        assertThat(savedOrder.getUser().getId()).isEqualTo(userId);
        assertThat(savedOrder.getTotalAmount()).isEqualTo(120000);
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(savedOrder.getOrderItems()).hasSize(1);

        OrderItem savedItem = savedOrder.getOrderItems().get(0);

        assertThat(savedItem.getId())
                .isNotNull()
                .isEqualTo(response.items().get(0).id());
        assertThat(savedItem.getOrder().getId()).isEqualTo(savedOrder.getId());
        assertThat(savedItem.getTicketProduct().getId()).isEqualTo(productId);
        assertThat(savedItem.getPrice()).isEqualTo(60000);
        assertThat(savedItem.getQuantity()).isEqualTo(2);

        TicketProduct updatedProduct = ticketProductRepository.findById(productId)
                .orElseThrow();

        assertThat(updatedProduct.getPrice()).isEqualTo(70000);
        assertThat(updatedProduct.getStock()).isEqualTo(100);
    }

    @Test
    @DisplayName("주문 취소를 DB에 반영하고 같은 주문의 재취소를 거부한다")
    void updateOrderByCancel_pendingOrder_persistsCancellationAndRejectsRetry() {
        // given
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("취소테스트")
                        .build()
        );

        TicketProduct product = ticketProductRepository.saveAndFlush(
                new TicketProduct(
                        "종일이용권",
                        60000,
                        "취소 통합 테스트 상품",
                        TicketType.ALL_DAY,
                        100,
                        TicketProductStatus.ON_SALE
                )
        );

        Long userId = user.getId();
        Long productId = product.getId();

        CreateOrderResponse createdOrder = orderService.createOrder(
                userId,
                new CreateOrderRequest(List.of(
                        new CreateOrderRequest.Item(productId, 2)
                ))
        );

        // when
        UpdateOrderResponse response = orderService.updateOrderByCancel(
                userId,
                createdOrder.orderId()
        );

        // 직접 수정 쿼리 이후 실제 DB에서 주문 상태를 다시 읽는다.
        entityManager.clear();

        Order cancelledOrder = orderRepository
                .findById(createdOrder.orderId())
                .orElseThrow();

        // then
        assertThat(response.orderId()).isEqualTo(createdOrder.orderId());
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        // 취소는 상태만 변경하며 주문 항목과 금액은 보존한다.
        assertThat(cancelledOrder.getTotalAmount()).isEqualTo(120000);
        assertThat(cancelledOrder.getOrderItems()).hasSize(1);

        // 생성 시 재고를 차감하지 않았으므로 취소 시에도 재고를 변경하지 않는다.
        assertThat(ticketProductRepository.findById(productId).orElseThrow()
                .getStock()).isEqualTo(100);

        assertThatThrownBy(() ->
                orderService.updateOrderByCancel(
                        userId,
                        createdOrder.orderId()
                ))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE)
                );
    }

    @Test
    @DisplayName("30분이 지난 결제 대기 주문만 자동 취소하고 반복 처리하지 않는다")
    void cancelExpiredPendingOrders_timeBoundary_updatesOnlyExpiredPendingOrders() {
        // given
        Instant now = Instant.parse("2026-10-23T01:30:00Z");
        Instant cutoff = now.minus(30, ChronoUnit.MINUTES);

        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("자동취소테스트")
                        .build()
        );

        Long expiredId = saveOrderForExpirationTest(
                user,
                OrderStatus.PENDING,
                now.minus(31, ChronoUnit.MINUTES)
        );
        Long boundaryId = saveOrderForExpirationTest(
                user,
                OrderStatus.PENDING,
                cutoff
        );
        Long recentId = saveOrderForExpirationTest(
                user,
                OrderStatus.PENDING,
                now.minus(29, ChronoUnit.MINUTES)
        );
        Long completedId = saveOrderForExpirationTest(
                user,
                OrderStatus.COMPLETED,
                now.minus(40, ChronoUnit.MINUTES)
        );
        Long cancelledId = saveOrderForExpirationTest(
                user,
                OrderStatus.CANCELLED,
                now.minus(40, ChronoUnit.MINUTES)
        );

        entityManager.clear();

        // when
        int cancelledCount = orderRepository.cancelExpiredPendingOrders(
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                cutoff,
                now
        );

        // then
        assertThat(cancelledCount).isEqualTo(2);

        assertThat(orderRepository.findById(expiredId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(boundaryId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(recentId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PENDING);
        assertThat(orderRepository.findById(completedId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(orderRepository.findById(cancelledId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        // 같은 기준으로 다시 실행해도 이미 취소된 주문을 재처리하지 않는다.
        int repeatedCount = orderRepository.cancelExpiredPendingOrders(
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                cutoff,
                now
        );

        assertThat(repeatedCount).isZero();
    }

    private Long saveOrderForExpirationTest(
            User user,
            OrderStatus status,
            Instant createdAt
    ) {
        Order order = Order.create(user, 60000);

        // 상태별 자동 취소 조건을 검증하기 위한 테스트 데이터를 구성한다.
        ReflectionTestUtils.setField(order, "status", status);
        orderRepository.saveAndFlush(order);

        Long orderId = order.getId();

        // 실제 대기 없이 시간 경계를 검증하도록 생성 시각을 테스트에서만 조정한다.
        entityManager.createQuery("""
                UPDATE Order o
                SET o.createdAt = :createdAt
                WHERE o.id = :orderId
                """)
                .setParameter("createdAt", createdAt)
                .setParameter("orderId", orderId)
                .executeUpdate();

        return orderId;
    }
}