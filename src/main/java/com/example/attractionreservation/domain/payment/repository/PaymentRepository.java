package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"order.user"})
    Optional<Payment> findWithLockByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"order"})
    Optional<Payment> findWithLockByPgId(String pgId);

    // 환불 처리에서 같은 결제의 환불을 순서대로 처리하기 위해 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockById(Long id);

    Optional<Payment> findByPgId(String pgId);

    @Query("""
    SELECT p
    FROM Payment p
    WHERE p.order.user.id = :userId AND p.pgId = :pgId
    """)
    Optional<Payment> findByPgIdAndUserId(String pgId, Long userId);
}
