package com.example.attractionreservation.domain.routeRecommendation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public record CreateRouteRecommendationRequest(
        @NotNull Long ticketId,
        @NotNull LocalDateTime startAt,
        @NotNull LocalDateTime endAt,
        @NotNull @Size(min = 1, max = 5) List<@NotNull Long> attractionIds
) {
}
