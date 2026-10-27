package com.example.attractionreservation.domain.routeRecommendation.algorithm;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import com.example.attractionreservation.global.util.KstTime;

//경로 탐색 결과
public record RouteSearchResult(
        LocalDateTime startAt, //경로 계산을 시작한 시각
        List<PlannedVisit> visits, // 선택된 방문 순서대로 정렬된 일정 목록
        List<Long> unreachableAttractionIds //후보에 있었지만 선택한 최적 경로에 들어가지 못한
) {
    //추천할 방문 일정이 하나도 없는지 확인
 public boolean isEmpty() {
     return visits.isEmpty();
 }

public int totalDurationMinutes() {
 if (isEmpty()){
     return 0;
 }
 return (int) Duration.between(startAt.atZone(KstTime.ZONE), estimatedEndAt().atZone(KstTime.ZONE)).toMinutes();
 }
//방문 순서상 마지막 일정의 종료 시각을 반환
 public LocalDateTime estimatedEndAt() {
     if (isEmpty()) {
        throw new IllegalStateException("빈 경로에는 종료 시각이 없습니다.");
     }
     return visits.get(visits.size() - 1).endsAt();
 }
}
