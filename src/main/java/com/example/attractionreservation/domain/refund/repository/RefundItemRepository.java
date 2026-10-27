package com.example.attractionreservation.domain.refund.repository;

import com.example.attractionreservation.domain.refund.dto.RefundedQuantity;
import com.example.attractionreservation.domain.refund.entity.RefundItem;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface RefundItemRepository extends JpaRepository<RefundItem, Long> {

    // 주문 상품별 환불 수량 합계. 환불이 없는 주문 상품은 결과에 포함되지 않는다.
    @Query("""
    SELECT new com.example.attractionreservation.domain.refund.dto.RefundedQuantity(ri.orderItem.id, SUM(ri.quantity))
    FROM RefundItem ri
    WHERE ri.orderItem.id IN :orderItemIds AND ri.refund.status IN :statuses
    GROUP BY ri.orderItem.id
    """)
    List<RefundedQuantity> sumQuantityByOrderItemIdInAndStatusIn(@Param("orderItemIds") Set<Long> orderItemIds,
                                                                 @Param("statuses") List<RefundStatus> statuses);
}
