package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}

