package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.time.LocalDateTime;

//탐색 중이거나 최종 선택된 경로의 방문 일정 한 건
public record PlannedVisit(
        int sequence, // 현제 경로에서의 방문 순번 1부터 시작
        Long attractionId, //방문할 어트랙션 ID
        String attractionName, //추천 결과에 사용할 어트랙션 이
        Long slotId, //이번 방문에서 선택한 슬롯 ID
        int moveMinutes, //이번 구간에 적용한 이동 시간
        LocalDateTime arrivalAt, //도착 예상 시간
        int waitMinutes, //도착후 탑승 전까지 기다리는 시간
        LocalDateTime startsAt, //선택한 슬롯의 탑승 시작 시각
        LocalDateTime endsAt, //선택한 슬롯의 탑승 종료 시각
        int rideMinutes //탑승 소요 시각
) {
}
