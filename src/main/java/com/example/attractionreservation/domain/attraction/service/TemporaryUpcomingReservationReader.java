package com.example.attractionreservation.domain.attraction.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

// 임시 구현체: 예약 도메인이 아직 없으므로 예약은 항상 0건이다.
@Component
public class TemporaryUpcomingReservationReader implements UpcomingReservationReader {

    @Override
    public long countUpcomingReservationsByAttractionId(Long attractionId, LocalDateTime now) {
        return 0L;
    }
}
