package com.example.attractionreservation.domain.order.service;

import com.example.attractionreservation.domain.order.dto.SearchAdminOrderCondition;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderListResponse;
import com.example.attractionreservation.domain.order.dto.SearchAdminOrderResponse;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.repository.OrderRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.attractionreservation.domain.order.dto.CreateAdminOrderRequest;
import com.example.attractionreservation.domain.order.dto.CreateOrderResponse;
import com.example.attractionreservation.domain.order.dto.UpdateOrderResponse;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.util.AfterCommit;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final TicketService ticketService;

    public SearchAdminOrderListResponse findOrders(
            SearchAdminOrderCondition condition
    ) {
        PageRequest pageable = PageRequest.of(
                condition.page(),
                condition.size(),
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        return SearchAdminOrderListResponse.from(
                orderRepository.findAllForAdmin(
                        condition.userId(),
                        condition.status(),
                        pageable
                )
        );
    }

    public SearchAdminOrderResponse findOrderById(Long orderId) {
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        String pgId = orderRepository.findPaymentPgIdByOrderId(orderId)
                .orElse(null);

        // 부분 환불할 티켓을 고를 수 있도록 주문에 발급된 티켓도 함께 반환한다.
        // 결제 전(PENDING) 주문은 티켓이 아직 없으므로 빈 목록이 된다.
        List<Ticket> tickets = ticketService.findTicketsByOrderId(orderId);

        return SearchAdminOrderResponse.from(order, pgId, tickets);
    }

    @Transactional
    public CreateOrderResponse createOrder(
            Long adminId,
            CreateAdminOrderRequest request
    ) {
        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (targetUser.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        // 주문 소유자는 관리자가 아닌 대상 회원이다.
        CreateOrderResponse response = orderService.createOrder(
                targetUser.getId(),
                request.toOrderRequest()
        );

        // 트랜잭션이 확정된 경우에만 완료 로그를 기록한다.
        AfterCommit.run(() -> log.info(
                "event=ADMIN_ORDER_CREATED "
                        + "userId={} targetUserId={} orderId={} totalAmount={} status={}",
                adminId,
                request.userId(),
                response.orderId(),
                response.totalAmount(),
                response.status()
        ));

        return response;
    }

    @Transactional
    public UpdateOrderResponse cancelOrder(
            Long adminId,
            Long orderId
    ) {
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() ->
                        new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        Long targetUserId = order.getUser().getId();

        // 기존 서비스의 상태 확인·동시 취소 방지·재고 복구를 재사용한다.
        UpdateOrderResponse response = orderService.updateOrderByCancel(
                targetUserId,
                orderId
        );

        AfterCommit.run(() -> log.info(
                "event=ADMIN_ORDER_CANCELLED "
                        + "userId={} targetUserId={} orderId={} status={}",
                adminId,
                targetUserId,
                response.orderId(),
                response.status()
        ));

        return response;
    }
}