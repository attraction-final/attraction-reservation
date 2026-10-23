package com.example.attractionreservation.domain.routeRecommendation.entity;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "route_recommendations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "ticket_id", nullable = false) //임시로 ID참조
    private Long ticketId;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "End_at", nullable = false)
    private LocalDateTime endAt;

    @Enumerated(EnumType.STRING)
    @Column(name ="start_location", nullable = false,length = 30)
    private StartLocation startLocation;

    @Enumerated(EnumType.STRING)
    @Column(name ="travel_time_basis", nullable = false, length = 30)
    private TravelTimeBasis travelTimeBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_type", nullable = false, length = 20)
    private RouteResultType resultType;

    @Column(name = "total_selected_attractions", nullable = false)
    private int totalSelectedAttractions;

    @Column(name = "total_move_minutes", nullable = false)
    private int totalMoveMinutes;

    @Column(name = "total_wait_minutes", nullable = false)
    private int totalWaitMinutes;

    @Column(name = "total_ride_minutes", nullable = false)
    private int totalRideMinutes;

    @Column(name = "total_duration_minutes", nullable = false)
    private int totalDurationMinutes;

    @Column(name = "estimated_end_at", nullable = false)
    private LocalDateTime estimatedEndAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;







}
