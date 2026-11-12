package com.example.attractionreservation.domain.routeRecommendation.dto;

import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

// 추천 경로 일괄 예약 결과 모든 방문을 예약했을 때만 만들어진다
public record CreateRouteReservationResponse(
        Long recommendationId,
        Long ticketId,
        int reservedCount,
        List<ReservedStop> reservations
) {
    // 예약 처리 순서(어트랙션 ID 순)와 상관없이 방문 순서(sequence 오름차순)로 정렬해 반환한다.
    public static CreateRouteReservationResponse of(Long recommendationId, Long ticketId, List<ReservedStop> reservations) {
        List<ReservedStop> sorted = reservations.stream()
                .sorted(Comparator.comparingInt(ReservedStop::sequence))
                .toList();
        return new CreateRouteReservationResponse(recommendationId, ticketId, sorted.size(), sorted);
    }

    // 예약된 방문 한 건. 시각은 추천 당시 값이 아니라 실제 예약된 슬롯의 값이다.
    public record ReservedStop(
            int sequence,
            Long attractionId,
            String attractionName,
            Long slotId,
            Long reservationId,
            String reservationNumber,
            LocalDateTime startsAt,
            LocalDateTime endsAt
    ) {
        public static ReservedStop of(RouteRecommendationStop stop, CreateReservationResponse reservation) {
            return new ReservedStop(
                    stop.getSequenceNumber(),
                    stop.getAttractionId(),
                    stop.getAttractionNameSnapshot(),
                    reservation.slotId(),
                    reservation.reservationId(),
                    reservation.reservationNumber(),
                    reservation.startsAt(),
                    reservation.endsAt()
            );
        }
    }
}