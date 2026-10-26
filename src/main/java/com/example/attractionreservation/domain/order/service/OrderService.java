package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.dto.SearchOrderResponse;
import com.example.attractionreservation.domain.order.dto.CreateOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.TicketProduct;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketProductStatus;
import com.example.attractionreservation.domain.ticketProduct.repository.TicketProductRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TicketProductRepository ticketProductRepository;
    private final UserRepository userRepository;

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
                .findAllById(productIds).stream()
                .collect(Collectors.toMap(
                        TicketProduct::getId,
                        Function.identity()
                ));

        for (CreateOrderRequest.Item item : request.items()) {
            TicketProduct ticketProduct = productsById.get(item.ticketProductId());

            if (ticketProduct == null) {
                throw new BusinessException(ErrorCode.TICKET_PRODUCT_NOT_FOUND);
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

            orderLines.add(new OrderLine(ticketProduct, item.quantity()));
        }

        Order order = Order.create(user, totalAmount);

        for (OrderLine orderLine : orderLines) {
            order.addOrderItem(
                    orderLine.ticketProduct(),
                    orderLine.quantity()
            );
        }

        // 주문 항목도 함께 저장한 뒤 DB에서 생성한 ID를 응답에 포함한다.
        Order savedOrder = orderRepository.saveAndFlush(order);

        return CreateOrderResponse.from(savedOrder);
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

    private int calculateTotalAmount(int totalAmount, int price, int quantity) {
        try {
            int itemAmount = Math.multiplyExact(price, quantity);
            return Math.addExact(totalAmount, itemAmount);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.ORDER_AMOUNT_OUT_OF_RANGE);
        }
    }

    private record OrderLine(
            TicketProduct ticketProduct,
            int quantity
    ) {
    }
}