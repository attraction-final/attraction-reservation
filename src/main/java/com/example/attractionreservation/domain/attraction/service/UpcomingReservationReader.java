package com.example.attractionreservation.domain.attraction.service;

import java.time.LocalDateTime;

// 특정 어트랙션의 다가오는 예약 건수를 조회하는 역할을 정의한 인터페이스
public interface UpcomingReservationReader {

    long countUpcomingReservationsByAttractionId(Long attractionId, LocalDateTime now);
}
