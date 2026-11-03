package com.example.attractionreservation;

import com.example.attractionreservation.domain.order.dto.SearchOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchOrderListResponse;
import com.example.attractionreservation.domain.payment.entity.Payment;
import com.example.attractionreservation.domain.payment.entity.enums.PaymentStatus;
import com.example.attractionreservation.domain.payment.repository.PaymentRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Clock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doReturn;
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

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private Clock clock;

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
        ), validVisitDate());

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

        // 주문과 같은 트랜잭션에서 결제 대기 결제가 생성되고 응답의 pgId로 찾을 수 있다.
        Payment savedPayment = paymentRepository.findByPgId(response.pgId())
                .orElseThrow();

        assertThat(savedPayment.getOrder().getId()).isEqualTo(savedOrder.getId());
        assertThat(savedPayment.getAmount()).isEqualTo(120000);
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("결제 생성에 실패하면 주문도 저장하지 않는다")
    void createOrder_paymentCreationFailed_rollsBackOrder() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // 서비스 실행 전에 테스트 데이터를 별도 트랜잭션으로 확정한다.
        Long[] ids = transaction.execute(status -> {
            User user = userRepository.saveAndFlush(
                    User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("결제생성롤백테스트")
                            .build()
            );

            // 금액이 0원이면 결제 생성이 거부되므로 결제 생성 실패를 재현할 수 있다.
            TicketProduct product = ticketProductRepository.saveAndFlush(
                    new TicketProduct(
                            "무료이용권",
                            0,
                            "결제 생성 실패 재현용 상품",
                            TicketType.ALL_DAY,
                            100,
                            TicketProductStatus.ON_SALE
                    )
            );

            return new Long[]{user.getId(), product.getId()};
        });

        try {
            CreateOrderRequest request = new CreateOrderRequest(List.of(
                    new CreateOrderRequest.Item(ids[1], 1)
            ), validVisitDate());

            assertThatThrownBy(() -> orderService.createOrder(ids[0], request))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.PAYMENT_INVALID_AMOUNT)
                    );

            // 실패한 서비스 트랜잭션 밖에서 주문이 남지 않았는지 다시 확인한다.
            transaction.executeWithoutResult(status ->
                    assertThat(orderRepository.findAll())
                            .noneMatch(order -> order.getUser().getId().equals(ids[0]))
            );
        } finally {
            transaction.executeWithoutResult(status -> {
                ticketProductRepository.deleteById(ids[1]);
                userRepository.deleteById(ids[0]);
            });
        }
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
                ), validVisitDate())
        );

        Order pendingOrder = orderRepository.findById(createdOrder.orderId())
                .orElseThrow();

        // 주문 생성 시 함께 만들어진 결제 대기 결제를 사용한다.
        Payment payment = paymentRepository.findByPgId(createdOrder.pgId())
                .orElseThrow();
        assertThat(payment.getOrder().getId()).isEqualTo(pendingOrder.getId());
        Long paymentId = payment.getId();

        // when
        UpdateOrderResponse response = orderService.updateOrderByCancel(
                userId,
                createdOrder.orderId()
        );

        // 결제 상태 변경도 DB에 반영한 뒤 다시 조회한다.
        entityManager.flush();
        entityManager.clear();

        Order cancelledOrder = orderRepository
                .findById(createdOrder.orderId())
                .orElseThrow();

        // then
        assertThat(response.orderId()).isEqualTo(createdOrder.orderId());
        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(paymentRepository.findById(paymentId)
                .orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);

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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("실제 자동 취소 서비스가 정확한 30분 경계를 적용한다")
    void updateExpiredOrdersByCancel_timeBoundary() {
        Instant now = Instant.parse("2026-10-23T01:30:00Z");
        Instant cutoff = now.minus(30, ChronoUnit.MINUTES);

        doReturn(now).when(clock).instant();

        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        Long[] ids = transaction.execute(status -> {
            User user = userRepository.saveAndFlush(
                    User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("시간경계테스트")
                            .build()
            );

            Long expiredId = saveOrderForExpirationTest(
                    user, OrderStatus.PENDING, cutoff.minusSeconds(1)
            );
            Long boundaryId = saveOrderForExpirationTest(
                    user, OrderStatus.PENDING, cutoff
            );
            Long recentId = saveOrderForExpirationTest(
                    user, OrderStatus.PENDING, cutoff.plusSeconds(1)
            );
            Long completedId = saveOrderForExpirationTest(
                    user, OrderStatus.COMPLETED, cutoff.minusSeconds(60)
            );

            // 주문은 결제와 함께 생성되므로 취소 처리 대상 주문에도 결제 대기 결제를 만든다.
            List<Long> paymentIds = new ArrayList<>();
            for (Long orderId : List.of(expiredId, boundaryId, recentId)) {
                paymentIds.add(paymentRepository.saveAndFlush(
                        Payment.create(orderRepository.findById(orderId).orElseThrow())
                ).getId());
            }

            return new Long[]{
                    user.getId(),
                    expiredId,
                    boundaryId,
                    recentId,
                    completedId,
                    paymentIds.get(0),
                    paymentIds.get(1),
                    paymentIds.get(2)
            };
        });

        try {
            int count = orderService.updateExpiredOrdersByCancel();

            assertThat(count).isEqualTo(2);

            transaction.executeWithoutResult(status -> {
                assertThat(orderRepository.findById(ids[1])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CANCELLED);

                assertThat(orderRepository.findById(ids[2])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CANCELLED);

                assertThat(orderRepository.findById(ids[3])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.PENDING);

                assertThat(orderRepository.findById(ids[4])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.COMPLETED);
            });

            assertThat(orderService.updateExpiredOrdersByCancel()).isZero();
        } finally {
            transaction.executeWithoutResult(status -> {
                // 결제가 주문을 참조하므로 결제를 먼저 지운다.
                for (int i = 5; i < ids.length; i++) {
                    paymentRepository.deleteById(ids[i]);
                }
                paymentRepository.flush();

                for (int i = 1; i <= 4; i++) {
                    orderRepository.deleteById(ids[i]);
                }

                orderRepository.flush();
                userRepository.deleteById(ids[0]);
            });
        }
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
                ), validVisitDate())
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

    // 방문일 검증 범위(오늘 ~ 오늘+29일) 안의 날짜를 사용한다.
    private LocalDate validVisitDate() {
        return LocalDate.now(KstTime.ZONE).plusDays(1);
    }

    private Long saveOrderForExpirationTest(
            User user,
            OrderStatus status,
            Instant createdAt
    ) {
        Order order = Order.create(user, 60000, validVisitDate());

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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("결제 취소가 거부되면 주문 취소도 롤백한다")
    void updateOrderByCancel_paymentRejected_rollsBackOrder() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // 서비스 실행 전에 테스트 데이터를 별도 트랜잭션으로 확정한다.
        Long[] ids = transaction.execute(status -> {
            User user = userRepository.saveAndFlush(
                    User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("취소롤백테스트")
                            .build()
            );

            Order order = orderRepository.saveAndFlush(
                    Order.create(user, 10000, validVisitDate())
            );

            // 결제 상태 검증에서 실패하도록 완료된 결제를 연결한다.
            Payment payment = Payment.create(order);
            payment.markAsPaid(Instant.now());
            paymentRepository.saveAndFlush(payment);

            return new Long[]{user.getId(), order.getId(), payment.getId()};
        });

        try {
            assertThatThrownBy(() ->
                    orderService.updateOrderByCancel(ids[0], ids[1]))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.PAYMENT_CANCEL_PROHIBITED)
                    );

            // 실패한 서비스 트랜잭션 밖에서 DB 상태를 다시 확인한다.
            transaction.executeWithoutResult(status -> {
                assertThat(orderRepository.findById(ids[1])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.PENDING);

                assertThat(paymentRepository.findById(ids[2])
                        .orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.PAID);
            });
        } finally {
            transaction.executeWithoutResult(status -> {
                paymentRepository.deleteById(ids[2]);
                paymentRepository.flush();
                orderRepository.deleteById(ids[1]);
                orderRepository.flush();
                userRepository.deleteById(ids[0]);
            });
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("자동 취소에서 실패한 주문은 롤백하고 다음 정상 주문은 취소한다")
    void updateExpiredOrdersByCancel_failureDoesNotBlockNextOrder() {
        TransactionTemplate transaction =
                new TransactionTemplate(transactionManager);

        Long[] ids = transaction.execute(status -> {
            User user = userRepository.saveAndFlush(
                    User.builder()
                            .email(UUID.randomUUID() + "@test.com")
                            .password("test-password-hash")
                            .nickname("자동취소격리테스트")
                            .build()
            );

            Instant now = Instant.now();

            // 실패할 주문을 먼저 조회하도록 더 오래된 시각으로 저장한다.
            Long failedOrderId = saveOrderForExpirationTest(
                    user,
                    OrderStatus.PENDING,
                    now.minus(32, ChronoUnit.MINUTES)
            );

            Long normalOrderId = saveOrderForExpirationTest(
                    user,
                    OrderStatus.PENDING,
                    now.minus(31, ChronoUnit.MINUTES)
            );

            Payment failedPayment = Payment.create(
                    orderRepository.findById(failedOrderId).orElseThrow()
            );
            failedPayment.markAsPaid(now);
            paymentRepository.saveAndFlush(failedPayment);

            Payment normalPayment = paymentRepository.saveAndFlush(
                    Payment.create(
                            orderRepository.findById(normalOrderId).orElseThrow()
                    )
            );

            return new Long[]{
                    user.getId(),
                    failedOrderId,
                    normalOrderId,
                    failedPayment.getId(),
                    normalPayment.getId()
            };
        });

        try {
            int count = orderService.updateExpiredOrdersByCancel();

            assertThat(count).isEqualTo(1);

            // 서비스 처리가 끝난 후 새 트랜잭션에서 실제 저장 상태를 확인한다.
            transaction.executeWithoutResult(status -> {
                // 결제가 완료된 주문의 취소 시도는 롤백된다.
                assertThat(orderRepository.findById(ids[1])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.PENDING);

                assertThat(paymentRepository.findById(ids[3])
                        .orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.PAID);

                // 앞 주문의 실패와 관계없이 다음 주문은 취소된다.
                assertThat(orderRepository.findById(ids[2])
                        .orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CANCELLED);

                assertThat(paymentRepository.findById(ids[4])
                        .orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.CANCELLED);
            });

            // 실패한 주문을 다시 만나도 예외를 밖으로 던지지 않는다.
            assertThat(orderService.updateExpiredOrdersByCancel()).isZero();
        } finally {
            transaction.executeWithoutResult(status -> {
                paymentRepository.deleteById(ids[3]);
                paymentRepository.deleteById(ids[4]);
                paymentRepository.flush();

                orderRepository.deleteById(ids[1]);
                orderRepository.deleteById(ids[2]);
                orderRepository.flush();

                userRepository.deleteById(ids[0]);
            });
        }
    }

    @Test
    @DisplayName("본인 주문만 최신순으로 페이지 조회하고 다른 회원 주문은 전체 개수에서도 제외한다")
    void findOrdersByUserId_multipleMembers_returnsOwnOrdersByPage() {
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
        ), validVisitDate());

        Long memberId = member.getId();

        CreateOrderResponse olderOrder =
                orderService.createOrder(memberId, request);
        CreateOrderResponse newerOrder =
                orderService.createOrder(memberId, request);

        // 타인 주문은 본인 목록과 전체 건수에서 제외한다.
        orderService.createOrder(otherMember.getId(), request);

        entityManager.flush();
        entityManager.clear();

        SearchOrderListResponse firstPage = orderService.findOrdersByUserId(
                memberId,
                new SearchOrderCondition(0, 1)
        );
        SearchOrderListResponse secondPage = orderService.findOrdersByUserId(
                memberId,
                new SearchOrderCondition(1, 1)
        );

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