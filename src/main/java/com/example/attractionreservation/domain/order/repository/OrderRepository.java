package com.example.attractionreservation.domain.order.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.example.attractionreservation.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;


public interface OrderRepository extends JpaRepository<Order, Long> {

    // 소유자 확인과 상세 응답에 필요한 주문자·항목을 함께 조회한다.
    @EntityGraph(attributePaths = {"user", "orderItems"})
    Optional<Order> findOrderById(Long id);

    // 인증된 회원의 주문만 DB에서 필터링하고 페이지 단위로 조회한다.
    Page<Order> findAllByUser_Id(Long userId, Pageable pageable);
}
