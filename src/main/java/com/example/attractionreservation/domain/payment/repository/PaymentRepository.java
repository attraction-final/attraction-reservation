package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, PaymentCustomRepository {
    Optional<Payment> findByIdAndOrderUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"order.user"})
    Optional<Payment> findWithLockByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"order"})
    Optional<Payment> findWithLockByPgId(String pgId);

    @Query("""
    SELECT p
    FROM Payment p
    WHERE p.order.user.id = :userId AND p.pgId = :pgId
    """)
    Optional<Payment> findByPgIdAndUserId(String pgId, Long userId);
}
