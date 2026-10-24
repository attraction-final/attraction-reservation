package com.example.attractionreservation.domain.routeRecommendation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "route_recommendation_stops")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendationStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private RouteRecommendation routeRecommendation;

    @Column(name = "attraction_id", nullable = false) //임시 id참조
    private Long attractionId;

    @Column(name = "attraction_slot_id", nullable = false) //임시 id참조
    private Long attractionSlotId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "attraction_name_snapshot", nullable = false, length = 100)
    private String attractionNameSnapshot;

    @Column(name = "estimated_move_min",nullable = false)
    private int estimatedMoveMin;

    @Column(name = "estimated_wait_min", nullable = false)
    private int estimatedWaitMin;

    @Column(name = "estimated_ride_min",nullable = false)
    private int estimatedRideMin;

    @Column(name = "estimated_arrival_at", nullable = false)
    private LocalDateTime estimatedArrivalAt;

    @Column(name = "slot_start_at", nullable = false)
    private LocalDateTime slotStartAt;

    @Column(name = "slot_end_at", nullable = false)
    private LocalDateTime slotEndAt;


}
