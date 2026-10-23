package com.example.attractionreservation;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
}