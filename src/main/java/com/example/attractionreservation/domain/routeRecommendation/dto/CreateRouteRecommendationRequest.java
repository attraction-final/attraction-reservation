package com.example.attractionreservation.domain.routeRecommendation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record CreateRouteRecommendationRequest(
        @NotNull Long ticketId,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        @NotNull @Size(min = 1, max = 5) List<@NotNull Long> attractionIds,
        //사용자 현재 위치 (선택). 둘 다 없으면 정문 출발, 둘 중 하나만 있으면 400. 저장하지 않는다
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
) {

    //위치 없이 요청 (정문 출발)
    public CreateRouteRecommendationRequest(
            Long ticketId, LocalDateTime startAt, LocalDateTime endAt, List<Long> attractionIds) {
        this(ticketId, startAt, endAt, attractionIds, null, null);
    }
}
