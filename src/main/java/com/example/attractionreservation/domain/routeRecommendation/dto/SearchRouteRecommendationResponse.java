package com.example.attractionreservation.domain.routeRecommendation.dto;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.global.util.KstTime;

import java.time.LocalDateTime;
import java.util.List;

//추천 경로 상세 조회 응답
//중첩 레코드로 만들었습니다.
public record SearchRouteRecommendationResponse(
        Long recommendationId,
        Long ticketId,
        LocalDateTime startAt,
        LocalDateTime endAt,
        StartLocation startLocation,
        TravelTimeBasis travelTimeBasis,
        RouteResultType resultType,
        int totalSelectedAttractions,
        int totalRecommendedAttractions,
        int totalMoveMinutes,
        int totalWaitMinutes,
        int totalRideMinutes,
        int totalDurationMinutes,
        LocalDateTime estimatedEndAt,
        LocalDateTime createdAt,
        List<Visit> visits,
        List<ExcludedAttraction> excludedAttractions
) {
    public static SearchRouteRecommendationResponse from(RouteRecommendation recommendation) {
        List<Visit> visits = recommendation.getStops().stream()
                .map(Visit::from)
                .toList();

        return new SearchRouteRecommendationResponse(
                recommendation.getId(),
                recommendation.getTicketId(),
                recommendation.getStartAt(),
                recommendation.getEndAt(),
                recommendation.getStartLocation(),
                recommendation.getTravelTimeBasis(),
                recommendation.getResultType(),
                recommendation.getTotalSelectedAttractions(),
                visits.size(),
                recommendation.getTotalMoveMinutes(),
                recommendation.getTotalWaitMinutes(),
                recommendation.getTotalRideMinutes(),
                recommendation.getTotalDurationMinutes(),
                recommendation.getEstimatedEndAt(),
                KstTime.toLocalDateTime(recommendation.getCreatedAt()),
                visits,
                recommendation.getExclusions().stream()
                        .map(ExcludedAttraction::from)
                        .toList()
        );
    }

    //방문 일정 1건
    public record Visit(
            int sequence,
            Long attractionId,
            String attractionName,
            Long attractionSlotIdm,
            int estimatedMoveMinutes,
            LocalDateTime estimatedArrivalAt,
            int estimatedWaitMinutes,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            int estimatedRideMinutes
    ) {
        public static Visit from(RouteRecommendationStop stop) {
            return new Visit(
                    stop.getSequenceNumber(),
                    stop.getAttractionId(),
                    stop.getAttractionNameSnapshot(),
                    stop.getAttractionSlotId(),
                    stop.getEstimatedMoveMin(),
                    stop.getEstimatedArrivalAt(),
                    stop.getEstimatedWaitMin(),
                    stop.getSlotStartAt(),
                    stop.getSlotEndAt(),
                    stop.getEstimatedRideMin()
            );
        }
    }

        //추천 제외 어트랙션 1건
        public record ExcludedAttraction(
                Long attractionId,
                String attractionName,
                String reasonCode,
                String reason
        ) {
            public static ExcludedAttraction from(RouteRecommendationExclusion exclusion) {
                return new ExcludedAttraction(
                        exclusion.getAttractionId(),
                        exclusion.getAttractionNameSnapshot(),
                        exclusion.getReasonCode().name(),
                        exclusion.getReasonMessage()
                );
            }
        }
    }


