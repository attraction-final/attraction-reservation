package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RouteRecommendationRepository extends JpaRepository<RouteRecommendation,Long> {

    @Query("""

            select r from RouteRecommendation r left join fetch r.stops where r.id = :id and r.deletedAt is null
""")
    Optional<RouteRecommendation> findDetailById(@Param("id") Long id);

    Page<RouteRecommendation> findAllByUserIdAndDeletedAtIsNull(Long userId, Pageable pageable);


    @Query("""
          select new com.example.attractionreservation.domain.routeRecommendation.repository.StopCount(
                  s.routeRecommendation.id, count(s))
                          from RouteRecommendationStop s
        where s.routeRecommendation.id in :ids
        group by s.routeRecommendation.id
        """)
    List<StopCount> countStopsByRecommendationIds(@Param("ids") Collection<Long> ids);
}