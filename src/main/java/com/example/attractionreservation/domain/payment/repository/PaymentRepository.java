package com.example.attractionreservation.domain.payment.repository;

import com.example.attractionreservation.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, PaymentCustomRepository {
    // 결제 상세 응답에 필요한 주문, 주문 항목, 티켓 상품을 함께 조회
    @EntityGraph(attributePaths = {"order", "order.orderItems", "order.orderItems.ticketProduct"})
    Optional<Payment> findByIdAndOrderUserId(Long id, Long userId);
}
