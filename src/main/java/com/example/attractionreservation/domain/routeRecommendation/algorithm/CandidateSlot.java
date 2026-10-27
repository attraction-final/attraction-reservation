package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.time.Duration;
import java.time.LocalDateTime;

//후보 선정을 조건을 통과한 슬롯
public record CandidateSlot(
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt
) {
    //슬롯의 탑승 소요 시간을 분 단위로 계산
    public int rideMinutes() {
        return (int) Duration.between(startsAt,endsAt).toMinutes();
    }
}
