package com.example.attractionreservation.domain.routeRecommendation.algorithm;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import com.example.attractionreservation.global.util.KstTime

//경로 탐색 결과
public record RouteSearchResult(
        LocalDateTime startAt, //경로 계산을 시작한 시각
        List<PlannedVisit> visits, // 선택한 경로의 방문 일정들 목록
        List<Long> unreachableAttractionIds //후보에 있었지만 선택한 최적 경로에 들어가지 못한
) {
 public boolean isEmpty() {
     return visits.isEmpty();
 }

public int totalDurationMinutes() {
 if (isEmpty()){
     return 0;
 }
 return (int) Duration.between(startAt.atZone(KstTime.ZONE), estimatedEndAt().atZone(KstTime.ZONE)).toMinutes();
 }

 public LocalDateTime estimatedEndAt() {
     if (isEmpty()) {
         return null;
     }
     return visits.get(visits.size() - 1).endsAt();
 }


}
