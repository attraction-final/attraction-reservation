package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "route_recommendation_exclusions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendationExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private RouteRecommendation routeRecommendation;

    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    @Column(name = "attraction_name_snapshot", nullable = false, length = 100)
    private String attractionNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private ExclusionReason reasonCode;

    @Column(name = "reason_message", nullable = false, length = 255)
    private String reasonMessage;
}
