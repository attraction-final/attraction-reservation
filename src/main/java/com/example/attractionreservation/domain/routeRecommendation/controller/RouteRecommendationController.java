package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/route-recommendations")
public class RouteRecommendationController {

    private final RouteRecommendationService routeRecommendationService;

    // 계산만 하고 저장하지 않으므로 항상 200. 로그인 여부는 SecurityConfig 가 검사한다.
    @PostMapping
    public ResponseEntity<ApiResponse<CreateRouteRecommendationResponse>> createRouteRecommendation(
            @Valid @RequestBody CreateRouteRecommendationRequest request
    ) {
        CreateRouteRecommendationResponse response = routeRecommendationService.recommend(request);
        return ResponseEntity.ok(ApiResponse.ok("최적 방문 순서를 추천했습니다.", response));
    }
}