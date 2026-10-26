package com.example.attractionreservation.domain.routeRecommendation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
//추천 경로에 포함된 방문 일정 한 건을 저장한다
@Getter
@Entity
@Table(name = "route_recommendation_stops")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteRecommendationStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //이 방문 일정이 속한 추천 경로
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private RouteRecommendation routeRecommendation;

    //방문할 어트랙션의 식별자 ->ID만 보관
    @Column(name = "attraction_id", nullable = false)
    private Long attractionId;

    //방문 일정에서 선택한 탑승 슬롯의 식별자
    @Column(name = "attraction_slot_id", nullable = false) 
    private Long attractionSlotId;

    //추천 경로 안에서의 방문 순서
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    //추천을 생성할 당시의 어트랙션 이름
    @Column(name = "attraction_name_snapshot", nullable = false, length = 100)
    private String attractionNameSnapshot;

    //이번 방문지까지 이동하는데 적용한 예상 시간
    @Column(name = "estimated_move_min",nullable = false)
    private int estimatedMoveMin;

    //도착 후 슬롯 시작까지 기다리는 예상 시간 -> 슬롯 시작 시각 - 도착 예상 시각으로 계산함
    @Column(name = "estimated_wait_min", nullable = false)
    private int estimatedWaitMin;

    //해당 슬롯에서 탑승하는 예상 시간 ->슬롯 종료 시각 - 슬롯 시작 시각으로 계산함
    @Column(name = "estimated_ride_min",nullable = false)
    private int estimatedRideMin;

    //해당 어트랙션에 도착 예상 시간
    @Column(name = "estimated_arrival_at", nullable = false)
    private LocalDateTime estimatedArrivalAt;

    //선택한 슬롯의 추천 당시 탑승 시작 시각
    @Column(name = "slot_start_at", nullable = false)
    private LocalDateTime slotStartAt;

    //선택한 슬롯의 추천 당시 탑승 종료 시각
    @Column(name = "slot_end_at", nullable = false)
    private LocalDateTime slotEndAt;


}
