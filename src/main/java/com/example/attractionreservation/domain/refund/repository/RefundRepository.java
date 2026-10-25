package com.example.attractionreservation.domain.refund.repository;

import com.example.attractionreservation.domain.refund.entity.Refund;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Refund> findWithLockById(Long id);

    // 결제 → 환불 순서로 잠그기 위해 환불을 영속성 컨텍스트에 올리지 않고 결제 ID만 조회한다.
    // 환불을 먼저 읽어 두면 이후 잠금 조회가 같은 인스턴스를 돌려줘 잠금 전에 읽은 상태로 판단하게 된다.
    @Query("SELECT r.payment.id FROM Refund r WHERE r.id = :id")
    Optional<Long> findPaymentIdById(@Param("id") Long id);

    // 같은 결제에서 다른 환불이 PG 취소 중이거나 확정 대기 중인지 확인한다. 같은 결제의 환불을 한 번에 하나씩 처리하기 위해 쓴다.
    boolean existsByPaymentIdAndStatusInAndIdNot(Long paymentId, Collection<RefundStatus> statuses, Long id);

    // PG 취소 결과를 조회로 확인할 차례인 환불. 조건은 Refund.isPgCheckable과 같다.
    @Query("""
    SELECT r.id
    FROM Refund r
    WHERE r.status = :pgPending AND r.nextRetryAt <= :now
    ORDER BY r.id
    """)
    List<Long> findPgCheckableIds(@Param("pgPending") RefundStatus pgPending,
                                  @Param("now") Instant now,
                                  Pageable pageable);

    // 결제의 상태별 환불 ID. 같은 결제의 PG_PENDING 환불은 한 번에 하나뿐이다.
    @Query("""
    SELECT r.id
    FROM Refund r
    WHERE r.payment.id = :paymentId AND r.status = :status
    ORDER BY r.id
    """)
    List<Long> findIdsByPaymentIdAndStatus(@Param("paymentId") Long paymentId,
                                           @Param("status") RefundStatus status);

    // 처리 대상 후보. 점유는 결제와 환불 행을 잠근 뒤 상태를 다시 확인해 결정한다.
    // 조건은 Refund.isProcessable과 같다(REQUESTED는 시각과 무관하게 대상).
    @Query("""
    SELECT r.id
    FROM Refund r
    WHERE r.status = :requested
       OR (r.status = :failed AND r.nextRetryAt <= :now)
       OR (r.status = :processing AND r.updatedAt <= :staleBefore)
    ORDER BY r.id
    """)
    List<Long> findProcessableIds(@Param("requested") RefundStatus requested,
                                  @Param("failed") RefundStatus failed,
                                  @Param("processing") RefundStatus processing,
                                  @Param("now") Instant now,
                                  @Param("staleBefore") Instant staleBefore,
                                  Pageable pageable);

    // PG 취소 금액과 맞출 미완료 환불 후보. 잠그기 전에 엔티티를 올리지 않도록 ID만 조회한다(findPaymentIdById 참고).
    @Query("""
    SELECT r.id
    FROM Refund r
    WHERE r.payment.id = :paymentId AND r.status IN :statuses AND r.refundAmount = :refundAmount
    """)
    List<Long> findIdsByPaymentIdAndStatusInAndRefundAmount(@Param("paymentId") Long paymentId,
                                                            @Param("statuses") List<RefundStatus> statuses,
                                                            @Param("refundAmount") int refundAmount);

    // 결제의 상태별 환불 금액 합계. COMPLETED로 조회하면 PG에 반영된 환불 금액이다.
    @Query("""
    SELECT COALESCE(SUM(r.refundAmount), 0)
    FROM Refund r
    WHERE r.payment.id = :paymentId AND r.status = :status
    """)
    long sumRefundAmountByPaymentIdAndStatus(@Param("paymentId") Long paymentId,
                                             @Param("status") RefundStatus status);
}
