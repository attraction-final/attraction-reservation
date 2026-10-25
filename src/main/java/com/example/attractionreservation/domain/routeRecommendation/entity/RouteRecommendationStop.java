package com.example.attractionreservation.domain.routeRecommendation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
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

    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    @Column(name = "attraction_slot_id", nullable = false)
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

    public static RouteRecommendationStop of(
            int sequenceNumber,
            Long attractionId,
            Long attractionSlotId,
            String attractionName,
            int moveMinutes,
            LocalDateTime arrivalAt,
            LocalDateTime slotStartAt,
            LocalDateTime slotEndAt
    ) {
        if (sequenceNumber < 1) {
            throw new IllegalStateException("방문 순서는 1부터 시작합니다.");
        }
        if (arrivalAt.isAfter(slotStartAt)) {
            throw new IllegalStateException("슬롯 시작 후에 도착하는 방문은 만들 수 없습니다.");
        }
        if (!slotStartAt.isBefore(slotEndAt)) {
            throw new IllegalStateException("슬롯 종료는 시작보다 뒤어야 합니다");
        }
        RouteRecommendationStop stop = new RouteRecommendationStop();
        stop.sequenceNumber = sequenceNumber;
        stop.attractionId = attractionId;
        stop.attractionSlotId = attractionSlotId;
        stop.attractionNameSnapshot = attractionName;
        stop.estimatedMoveMin = moveMinutes;
        stop.estimatedArrivalAt = arrivalAt;
        stop.slotStartAt = slotStartAt;
        stop.slotEndAt = slotEndAt;
        stop.estimatedWaitMin = (int) Duration.between(arrivalAt, slotStartAt).toMinutes();
        stop.estimatedRideMin = (int) Duration.between(slotStartAt, slotEndAt).toMinutes();
        return stop;
    }
    void assignRouteRecommendation(RouteRecommendation recommendation) {
        this.routeRecommendation = recommendation;
    }
}
