package com.example.attractionreservation.domain.routeRecommendation.dto;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.global.util.KstTime;

import java.time.LocalDateTime;


public record SearchRouteRecommendationSummaryResponse(
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
        LocalDateTime createdAt
) {


    public static SearchRouteRecommendationSummaryResponse of(RouteRecommendation recommendation, int recommendedCount) {
        return new SearchRouteRecommendationSummaryResponse(
                recommendation.getId(),
                recommendation.getTicketId(),
                recommendation.getStartAt(),
                recommendation.getEndAt(),
                recommendation.getStartLocation(),
                recommendation.getTravelTimeBasis(),
                recommendation.getResultType(),
                recommendation.getTotalSelectedAttractions(),
                recommendedCount,
                recommendation.getTotalMoveMinutes(),
                recommendation.getTotalWaitMinutes(),
                recommendation.getTotalRideMinutes(),
                recommendation.getTotalDurationMinutes(),
                recommendation.getEstimatedEndAt(),
                KstTime.toLocalDateTime(recommendation.getCreatedAt())
        );
    }
}