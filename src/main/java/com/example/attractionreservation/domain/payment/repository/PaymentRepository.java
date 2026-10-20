package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @EntityGraph(attributePaths = {"order.user"})
    Optional<Payment> findByOrderId(Long orderId);
}
