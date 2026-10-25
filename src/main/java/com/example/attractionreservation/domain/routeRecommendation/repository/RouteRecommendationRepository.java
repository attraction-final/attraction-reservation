package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RouteRecommendationRepository extends JpaRepository<RouteRecommendation,Long> {

    @Query("""

            select r from RouteRecommendation r left join fetch r.stops where r.id = :id and r.deletedAt is null
""")
    Optional<RouteRecommendation> findDetailById(@Param("id" )Long id);
}
