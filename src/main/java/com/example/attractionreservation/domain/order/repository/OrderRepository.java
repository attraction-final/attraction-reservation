package com.example.attractionreservation.domain.order.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.order.entity.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 상세 응답과 소유자 확인에 필요한 정보를 함께 조회한다.
    @EntityGraph(attributePaths = {"user", "orderItems"})
    Optional<Order> findOrderById(Long id);

    // 인증된 회원의 주문만 페이지 단위로 조회한다.
    Page<Order> findAllByUser_Id(Long userId, Pageable pageable);

    // 주문 목록 화면에 필요한 주문 상품과 상품 이름을 한 번의 쿼리로 조회한다.
    // 주문마다 따로 조회하면 N+1 문제가 생기므로 주문 ID 목록을 IN 조건으로 묶는다.
    // 컬렉션 fetch join과 페이징을 함께 쓰지 않기 위해 주문 목록 조회와 분리했다.
    @Query("""
            SELECT oi
            FROM OrderItem oi
            JOIN FETCH oi.ticketProduct
            WHERE oi.order.id IN :orderIds
            ORDER BY oi.id ASC
            """)
    List<OrderItem> findOrderItemsWithProductByOrderIds(
            @Param("orderIds") List<Long> orderIds
    );

    // 실제 수정 시점에도 소유자와 PENDING 상태를 확인한다.
    // 직접 수정 쿼리이므로 수정 시각도 함께 갱신한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = :cancelledStatus,
                o.updatedAt = :updatedAt
            WHERE o.id = :orderId
                AND o.user.id = :userId
                AND o.status = :pendingStatus
            """)
    int cancelPendingOrder(
            @Param("orderId") Long orderId,
            @Param("userId") Long userId,
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("cancelledStatus") OrderStatus cancelledStatus,
            @Param("updatedAt") Instant updatedAt
    );

    // 자동 취소 대상을 오래된 순서로 조회하고 주문자 정보도 함께 가져온다.
    @Query("""
            SELECT o
            FROM Order o
            JOIN FETCH o.user
            WHERE o.status = :pendingStatus
                AND o.createdAt <= :cutoff
            ORDER BY o.createdAt ASC, o.id ASC
            """)
    List<Order> findExpiredPendingOrders(
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    // 주문 상세 응답에 필요한 결제번호만 조회한다.
    @Query("""
        SELECT p.pgId
        FROM Payment p
        WHERE p.order.id = :orderId
        """)
    Optional<String> findPaymentPgIdByOrderId(
            @Param("orderId") Long orderId
    );

    // 목록에 필요한 주문자 정보를 함께 조회한다.
    @EntityGraph(attributePaths = {"user"})
    @Query("""
        SELECT o
        FROM Order o
        WHERE (:userId IS NULL OR o.user.id = :userId)
          AND (:status IS NULL OR o.status = :status)
        """)
    Page<Order> findAllForAdmin(
            @Param("userId") Long userId,
            @Param("status") OrderStatus status,
            Pageable pageable
    );
}