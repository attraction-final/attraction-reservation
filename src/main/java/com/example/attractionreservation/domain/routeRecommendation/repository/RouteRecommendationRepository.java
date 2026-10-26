package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRecommendationRepository extends JpaRepository<RouteRecommendation,Long> {

}
