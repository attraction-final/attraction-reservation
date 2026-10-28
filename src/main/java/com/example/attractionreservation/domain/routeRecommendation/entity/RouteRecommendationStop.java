package com.example.attractionreservation.domain.routeRecommendation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import com.example.attractionreservation.global.util.KstTime;
//추천 경로에 포한됨 방문 일정 한 건을 저장 ->어트랙션 이름과 슬롯 시각은 추천 당시 값으로 저장
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

    //도착 후 슬롯 시작까지 기다리는 에상 시간 ->슬롯 시작 시각 - 도착 예상 시각으로 계산함
    @Column(name = "estimated_wait_min", nullable = false)
    private int estimatedWaitMin;

    //해당 슬롯에서 탑승하는 예상 시간 -> 슬롯 종료 시각 - 슬롯 시작 시각으로 계산함
    @Column(name = "estimated_ride_min",nullable = false)
    private int estimatedRideMin;

    //해당 어트랙션에 도착할 것으로 계산된 시각
    @Column(name = "estimated_arrival_at", nullable = false)
    private LocalDateTime estimatedArrivalAt;

    //선택한 슬롯의 추천 당시 탑승 시작 시각
    @Column(name = "slot_start_at", nullable = false)
    private LocalDateTime slotStartAt;

    //선택한 슬롯의 추천 당시 탑승 종료 시각
    @Column(name = "slot_end_at", nullable = false)
    private LocalDateTime slotEndAt;

    //방문 일정 한 건을 만드는 정적 팩토리 메서드
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
        } //전체 경로에서 순번이 중복되거나 빠졌는지는 검사하지 않음
        //도착 시간과 슬롯 시작 시각이 같으면 허용 ->대기 시간 0분
        if (arrivalAt.isAfter(slotStartAt)) {
            throw new IllegalStateException("슬롯 시작 후에 도착하는 방문은 만들 수 없습니다.");
        }
        if (!slotStartAt.isBefore(slotEndAt)) {
            throw new IllegalStateException("슬롯 종료는 시작보다 뒤어야 합니다");
        }
        //식별 정보랑 추천 정보를 저장하기
        RouteRecommendationStop stop = new RouteRecommendationStop();
        stop.sequenceNumber = sequenceNumber;
        stop.attractionId = attractionId;
        stop.attractionSlotId = attractionSlotId;
        stop.attractionNameSnapshot = attractionName;
        stop.estimatedMoveMin = moveMinutes; //이동 시간이랑 도착 시간은 탐색 로직이 계산한 값 사용
        stop.estimatedArrivalAt = arrivalAt;
        stop.slotStartAt = slotStartAt;
        stop.slotEndAt = slotEndAt;
        //toMinutes() ->초 단위 나머지를 버리고 정수 분 반환
        stop.estimatedWaitMin = (int) Duration.between(arrivalAt.atZone(KstTime.ZONE), slotStartAt.atZone(KstTime.ZONE)).toMinutes();
        stop.estimatedRideMin = (int) Duration.between(slotStartAt.atZone(KstTime.ZONE), slotEndAt.atZone(KstTime.ZONE)).toMinutes();
        return stop;
    }
    //이 방문 일정의 부모 추천 경로를 연결
    void assignRouteRecommendation(RouteRecommendation recommendation) {
        this.routeRecommendation = recommendation;
    }
}
