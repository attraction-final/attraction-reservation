package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.service.UpcomingReservationReader;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;


// 어트랙션 도메인이 "이용 예정 예약이 몇 건인지" 물어보면
// 예약 도메인이 실제 DB를 세어서 대답해 주는 클래스
@Component
@RequiredArgsConstructor
public class ReservationUpcomingReservationReader implements UpcomingReservationReader {

    private final ReservationRepository reservationRepository;

    @Override
    public long countUpcomingReservationsByAttractionId(Long attractionId, LocalDateTime now) {
        return reservationRepository.countUpcomingByAttractionId(
                attractionId,
                ReservationStatus.RESERVED,
                now
        );
    }
}
