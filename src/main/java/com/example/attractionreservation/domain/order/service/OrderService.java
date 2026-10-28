package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.payment.service.PaymentService;
import com.example.attractionreservation.domain.order.dto.SearchOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchOrderListResponse;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.ticketProduct.service.TicketProductService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TicketProductRepository ticketProductRepository;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final OrderCancelProcessor orderCancelProcessor;
    private final Clock clock;
    private final TicketProductService ticketProductService;

    private static final int MAX_VISIT_DAYS = 30;

    @Transactional
    public CreateOrderResponse createOrder(
            Long userId,
            CreateOrderRequest request
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.UNAUTHORIZED));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        List<OrderLine> orderLines = new ArrayList<>();
        int totalAmount = 0;

        // 중복 상품 ID를 제거하고 필요한 상품을 일괄 조회한다.
        Set<Long> productIds = request.items().stream()
                .map(CreateOrderRequest.Item::ticketProductId)
                .collect(Collectors.toSet());

        Map<Long, TicketProduct> productsById = ticketProductRepository
                .findAllWithUpdateByIdInOrderById(productIds).stream()
                .collect(Collectors.toMap(
                        TicketProduct::getId,
                        Function.identity()
                ));

        // 방문일 검증
        validateVisitDate(request.visitDate(), LocalDate.now(clock));

        for (CreateOrderRequest.Item item : request.items()) {
            TicketProduct ticketProduct =
                    productsById.get(item.ticketProductId());

            if (ticketProduct == null) {
                throw new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND);
            }

            // 품절 상품은 판매 중지와 구분해 재고 부족으로 응답한다.
            if (ticketProduct.getStatus() == TicketProductStatus.OUT_OF_STOCK) {
                throw new BusinessException(ErrorCode.TICKET_PRODUCT_STOCK_INSUFFICIENT);
            }

            if (ticketProduct.getStatus() != TicketProductStatus.ON_SALE) {
                throw new BusinessException(ErrorCode.ORDER_PRODUCT_NOT_ON_SALE);
            }

            // 클라이언트의 금액을 받지 않고 서버가 조회한 상품 가격으로 계산한다.
            totalAmount = calculateTotalAmount(
                    totalAmount,
                    ticketProduct.getPrice(),
                    item.quantity()
            );

            // 재고 차감
            ticketProduct.decreaseStock(item.quantity());

            orderLines.add(new OrderLine(ticketProduct, item.quantity()));
        }

        Order order = Order.create(user, totalAmount, request.visitDate());

        for (OrderLine orderLine : orderLines) {
            order.addOrderItem(
                    orderLine.ticketProduct(),
                    orderLine.quantity()
            );
        }

        // 주문 항목도 함께 저장한 뒤 DB에서 생성한 ID를 응답에 포함한다.
        Order savedOrder = orderRepository.saveAndFlush(order);

        // 결제창 연동에 쓸 결제를 주문과 같은 트랜잭션에서 만든다. 결제 생성이 실패하면 주문도 롤백된다.
        String pgId = paymentService.createPayment(savedOrder);

        return CreateOrderResponse.from(savedOrder, pgId);
    }

    @Transactional(readOnly = true)
    public SearchOrderResponse findOrderById(Long userId, Long orderId) {
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        // 주문 ID를 알고 있어도 다른 회원의 주문은 조회할 수 없다.
        if (!order.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_ACCESS_DENIED);
        }

        return SearchOrderResponse.from(order);
    }

    @Transactional
    public UpdateOrderResponse updateOrderByCancel(
            Long userId,
            Long orderId
    ) {
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_CANCEL_ACCESS_DENIED);
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        // 결제 → 주문 순서로 잠금을 획득한다.
        paymentService.lockPaymentByOrderIdIfExists(orderId);

        // 조회 후 상태가 바뀔 수 있으므로 실제 수정 시에도 PENDING 조건을 확인한다.
        int updatedCount = orderRepository.cancelPendingOrder(
                orderId,
                userId,
                OrderStatus.PENDING,
                OrderStatus.CANCELLED,
                Instant.now()
        );

        if (updatedCount == 0) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE);
        }

        // 주문 티켓 상품 id 및 수량 map 생성
        Map<Long, Integer> quantitiesByTicketProductId = order.getQuantityByTicketProductId();

        // 재고 복구
        ticketProductService.updateTicketProductsStockIncrease(quantitiesByTicketProductId);

        // 결제 취소가 실패하면 같은 트랜잭션의 주문 취소도 롤백한다.
        paymentService.cancelledByUser(userId, orderId);

        // 수정 쿼리 실행 후 영속성 컨텍스트가 비워지므로 기존 엔티티를 재사용하지 않는다.
        return UpdateOrderResponse.of(
                orderId,
                OrderStatus.CANCELLED
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int updateExpiredOrdersByCancel() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(30, ChronoUnit.MINUTES);

        List<Order> expiredOrders = orderRepository.findExpiredPendingOrders(
                OrderStatus.PENDING,
                cutoff,
                PageRequest.of(0, 100)
        );

        int cancelledCount = 0;

        for (Order order : expiredOrders) {
            Long orderId = order.getId();
            Long userId = order.getUser().getId();

            try {
                if (orderCancelProcessor.cancelExpiredOrder(
                        orderId,
                        userId,
                        now
                )) {
                    cancelledCount++;
                }
            } catch (BusinessException exception) {
                // 실패한 주문은 해당 트랜잭션에서 롤백하고 다음 주문을 처리한다.
                log.warn(
                        "만료 주문 자동 취소 실패: orderId={}, errorCode={}",
                        orderId,
                        exception.getErrorCode()
                );
            }
        }

        return cancelledCount;
    }

    @Transactional(readOnly = true)
    public SearchOrderListResponse findOrdersByUserId(
            Long userId,
            SearchOrderCondition condition
    ) {
        // 생성 시각이 같아도 조회 순서가 일정하도록 ID를 보조 정렬로 사용한다.
        Pageable pageable = PageRequest.of(
                condition.page(),
                condition.size(),
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        Page<Order> orders = orderRepository.findAllByUser_Id(userId, pageable);

        return SearchOrderListResponse.from(orders);
    }

    private int calculateTotalAmount(int totalAmount, int price, int quantity) {
        try {
            int itemAmount = Math.multiplyExact(price, quantity);
            return Math.addExact(totalAmount, itemAmount);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_AMOUNT_OUT_OF_RANGE);
        }
    }

    private void validateVisitDate(LocalDate visitDate, LocalDate today) {
        LocalDate lastDate = today.plusDays(MAX_VISIT_DAYS - 1);

        if (visitDate.isBefore(today) || visitDate.isAfter(lastDate)) {
            throw new BusinessException(ErrorCode.ORDER_INVALID_VISIT_DATE);
        }
    }

    private record OrderLine(
            TicketProduct ticketProduct,
            int quantity
    ) {
    }
}