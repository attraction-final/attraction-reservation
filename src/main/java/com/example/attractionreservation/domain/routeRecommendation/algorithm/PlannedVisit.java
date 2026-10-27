package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.time.LocalDateTime;

//경로 안의 방문 1건
public record PlannedVisit(
        int sequence, // 경로 안의 몇번째 방문인지(계산한 경로의 방문 순번) 1부터 시작
        Long attractionId,
        String attractionName,
        Long slotId, //어떤 회차인지
        int moveMinutes, //이번 구간의 이동시간으로 적용한 추정값
        LocalDateTime arrivalAt, //도착 예상 시간
        int waitMinutes, //도착후 탑승 전까지 기다리는 시간
        LocalDateTime startsAt, //선택한 슬롯의 탑승 시작 시각
        LocalDateTime endsAt, //선택한 슬롯의 탑승 종료 시각
        int rideMinutes //탑승 소요 시각
) {
}
