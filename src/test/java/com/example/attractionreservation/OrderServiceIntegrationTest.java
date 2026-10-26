package com.example.attractionreservation;

import com.example.attractionreservation.domain.order.dto.SearchOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
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

import java.time.LocalDateTime;
import java.time.ZoneId;
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

    @Test
    @DisplayName("실제 DB의 본인 주문과 항목을 조회하고 생성 시각을 KST로 반환한다")
    void findOrderById_savedOrder_returnsDetailFromDatabase() {
        // given
        User user = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("조회테스트")
                        .build()
        );

        TicketProduct product = ticketProductRepository.saveAndFlush(
                new TicketProduct(
                        "종일이용권",
                        60000,
                        "주문 조회 테스트 상품",
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

        entityManager.flush();
        entityManager.clear();

        // DB에 저장된 실제 시각을 기준으로 기대값을 만든다.
        Order storedOrder = orderRepository.findById(createdOrder.orderId())
                .orElseThrow();
        LocalDateTime expectedCreatedAt = LocalDateTime.ofInstant(
                storedOrder.getCreatedAt(),
                ZoneId.of("Asia/Seoul")
        );

        // 상세 조회가 기존 메모리 객체 대신 DB에서 데이터를 가져오도록 한다.
        entityManager.clear();

        // when
        SearchOrderResponse response = orderService.findOrderById(
                userId,
                createdOrder.orderId()
        );

        // then
        assertThat(response.orderId()).isEqualTo(createdOrder.orderId());
        assertThat(response.totalAmount()).isEqualTo(120000);
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.createdAt()).isEqualTo(expectedCreatedAt);
        assertThat(response.items()).containsExactly(
                new SearchOrderResponse.Item(
                        createdOrder.items().get(0).id(),
                        productId,
                        60000,
                        2
                )
        );
    }

    @Test
    @DisplayName("본인 주문만 최신순으로 페이지 조회하고 다른 회원 주문은 전체 개수에서도 제외한다")
    void findOrdersByUserId_multipleMembers_returnsOwnOrdersByPage() {
        // given
        User member = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("목록회원")
                        .build()
        );
        User otherMember = userRepository.saveAndFlush(
                User.builder()
                        .email(UUID.randomUUID() + "@test.com")
                        .password("test-password-hash")
                        .nickname("다른회원")
                        .build()
        );

        TicketProduct product = ticketProductRepository.saveAndFlush(
                new TicketProduct(
                        "종일이용권",
                        60000,
                        "주문 목록 테스트 상품",
                        TicketType.ALL_DAY,
                        100,
                        TicketProductStatus.ON_SALE
                )
        );

        CreateOrderRequest request = new CreateOrderRequest(List.of(
                new CreateOrderRequest.Item(product.getId(), 1)
        ));

        Long memberId = member.getId();

        CreateOrderResponse olderOrder =
                orderService.createOrder(memberId, request);
        CreateOrderResponse newerOrder =
                orderService.createOrder(memberId, request);

        // 가장 나중에 생성한 타인 주문도 본인 목록에는 포함되면 안 된다.
        orderService.createOrder(otherMember.getId(), request);

        entityManager.flush();
        entityManager.clear();

        // when
        SearchOrderListResponse firstPage = orderService.findOrdersByUserId(
                memberId,
                new SearchOrderCondition(0, 1)
        );
        SearchOrderListResponse secondPage = orderService.findOrdersByUserId(
                memberId,
                new SearchOrderCondition(1, 1)
        );

        // then
        assertThat(firstPage.content())
                .extracting(SearchOrderListResponse.Item::orderId)
                .containsExactly(newerOrder.orderId());
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(1);
        assertThat(firstPage.totalElements()).isEqualTo(2L);
        assertThat(firstPage.totalPages()).isEqualTo(2);

        assertThat(secondPage.content())
                .extracting(SearchOrderListResponse.Item::orderId)
                .containsExactly(olderOrder.orderId());
        assertThat(secondPage.page()).isEqualTo(1);
        assertThat(secondPage.totalElements()).isEqualTo(2L);
        assertThat(secondPage.totalPages()).isEqualTo(2);
    }
}