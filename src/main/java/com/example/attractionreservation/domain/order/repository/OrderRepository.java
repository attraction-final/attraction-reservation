package com.example.attractionreservation.domain.order.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 소유자 확인과 상세 응답에 필요한 주문자·항목을 함께 조회한다.
    @EntityGraph(attributePaths = {"user", "orderItems"})
    Optional<Order> findOrderById(Long id);

    /**
     * 본인의 결제 대기 주문만 취소한다.
     *
     * 상태 확인과 변경을 하나의 쿼리로 실행하여,
     * 동시에 취소 요청이 들어와도 먼저 처리된 요청만 성공하도록 한다.
     *
     * @return 변경된 주문 수. 1이면 취소 성공, 0이면 조건 불일치
     */
    // 직접 수정 쿼리는 JPA Auditing을 거치지 않으므로 수정 시각도 함께 갱신한다.
    // 실행 전 변경 사항을 DB에 반영하고, 실행 후 메모리에 남은 오래된 엔티티 정보를 비운다.
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

    /**
     * 취소 기준 시각까지 생성된 결제 대기 주문을 자동 취소한다.
     *
     * 이미 완료되거나 취소된 주문은 제외하며,
     * 스케줄러가 반복 실행되어도 취소된 주문을 다시 변경하지 않는다.
     *
     * @return 자동 취소한 주문 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE Order o
            SET o.status = :cancelledStatus,
                o.updatedAt = :updatedAt
            WHERE o.status = :pendingStatus
                AND o.createdAt <= :cutoff
            """)
    int cancelExpiredPendingOrders(
            @Param("pendingStatus") OrderStatus pendingStatus,
            @Param("cancelledStatus") OrderStatus cancelledStatus,
            @Param("cutoff") Instant cutoff,
            @Param("updatedAt") Instant updatedAt
    );
}