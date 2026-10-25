package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationSummaryResponse;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.global.response.ApiResponse;
import com.example.attractionreservation.global.response.PageableDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/route-recommendations")
public class RouteRecommendationController {
    private final RouteRecommendationService routeRecommendationService;

    @GetMapping("/{recommendationId}")
    public ResponseEntity<ApiResponse<SearchRouteRecommendationResponse>> getRouteRecommendationById(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recommendationId
    ) {
        SearchRouteRecommendationResponse response = routeRecommendationService.findRouteRecommendationById(userId, recommendationId);
        return ResponseEntity.ok(ApiResponse.ok("추천 경로 상세 조회에 성공했습니다.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageableDto<SearchRouteRecommendationSummaryResponse>>> getRouteRecommendationAll(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        PageableDto<SearchRouteRecommendationSummaryResponse> response =
                routeRecommendationService.findRouteRecommendations(userId, page, size);

        return ResponseEntity.ok(
                ApiResponse.ok("추천 경로 목록 조회에 성공했습니다.", response)
        );
    }
}

