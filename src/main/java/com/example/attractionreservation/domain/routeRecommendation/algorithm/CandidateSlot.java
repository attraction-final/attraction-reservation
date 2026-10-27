package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import com.example.attractionreservation.global.util.KstTime;

import java.time.Duration;
import java.time.LocalDateTime;

//후보 선정을 통과해서 예약 가능해 보이는 슬롯
public record CandidateSlot(
        Long slotId,
        LocalDateTime startsAt,
        LocalDateTime endsAt
) {
    public int rideMinutes() {
        return (int) Duration.between(startsAt.atZone(KstTime.ZONE), endsAt.atZone(KstTime.ZONE)).toMinutes();
    }
}