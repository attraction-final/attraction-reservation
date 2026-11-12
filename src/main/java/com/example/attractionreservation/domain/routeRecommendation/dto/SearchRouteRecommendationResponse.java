package com.example.attractionreservation.domain.routeRecommendation.dto;

import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.global.util.KstTime;

import java.time.LocalDateTime;
import java.util.List;

//추천 경로 결과 (저장된 FULL·PARTIAL 추천, 또는 저장하지 않는 NONE)
//중첩 레코드로 만듬
public record SearchRouteRecommendationResponse(
        Long recommendationId, // NONE 이면 null (저장하지 않음)
        Long ticketId,
        LocalDateTime startAt, // 현재 시각을 분 단위로 올린 값
        LocalDateTime endAt, // 방문일 폐장 시각
        RouteResultType resultType,
        int totalSelectedAttractions,
        int totalRecommendedAttractions,
        int totalMoveMinutes,
        int totalWaitMinutes,
        int totalRideMinutes,
        int totalDurationMinutes,
        LocalDateTime estimatedEndAt,
        LocalDateTime createdAt,
        boolean estimatedValues, // 이동·대기 시간이 예상값임을 표시 항상 true
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
                recommendation.getResultType(),
                recommendation.getTotalSelectedAttractions(),
                visits.size(),
                recommendation.getTotalMoveMinutes(),
                recommendation.getTotalWaitMinutes(),
                recommendation.getTotalRideMinutes(),
                recommendation.getTotalDurationMinutes(),
                recommendation.getEstimatedEndAt(),
                KstTime.toLocalDateTime(recommendation.getCreatedAt()),
                true,
                visits,
                recommendation.getExclusions().stream()
                        .map(ExcludedAttraction::from)
                        .toList()
        );
    }

    //추천 가능한 경로가 없을 때의 결과. 저장하지 않으므로 ID·생성 시각·종료 예상 시각이 없다.
    public static SearchRouteRecommendationResponse none(
            Long ticketId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            int totalSelectedAttractions,
            List<ExcludedAttraction> excludedAttractions
    ) {
        return new SearchRouteRecommendationResponse(
                null,
                ticketId,
                startAt,
                endAt,
                RouteResultType.NONE,
                totalSelectedAttractions,
                0,
                0, 0, 0, 0,
                null,
                null,
                true,
                List.of(),
                excludedAttractions
        );
    }

    public boolean isNone() {
        return resultType == RouteResultType.NONE;
    }

    //방문 일정 1건
    public record Visit(
            int sequence,
            Long attractionId,
            String attractionName,
            Long attractionSlotId,
            int estimatedMoveMinutes,
            boolean moveEstimated, // 이동 시간이 추정값인지 (첫 구간은 항상 true, 이후는 구간의 is_estimated)
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
                    stop.isMoveEstimated(),
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

        public static ExcludedAttraction of(Long attractionId, String attractionName, ExclusionReason reason) {
            return new ExcludedAttraction(attractionId, attractionName, reason.name(), reason.getMessage());
        }
    }
}