package com.example.attractionreservation.domain.order.repository;

import com.example.attractionreservation.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}