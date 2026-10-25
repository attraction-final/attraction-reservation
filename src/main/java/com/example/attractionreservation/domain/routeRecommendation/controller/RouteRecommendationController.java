package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/route-recommendations")
public class RouteRecommendationController {

    private final RouteRecommendationService routeRecommendationService;

    @PostMapping
    public ResponseEntity<ApiResponse<SearchRouteRecommendationResponse>> createRouteRecommendation(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateRouteRecommendationRequest request
    ) {
        SearchRouteRecommendationResponse response = routeRecommendationService.createRouteRecommendation(userId, request);

        // 추천 없음은 오류가 아니라 정상 결과 → 200, 저장된 추천은 새 리소스 → 201
        if (response.isNone()) {
            return ResponseEntity.ok(ApiResponse.ok("선택한 조건에서 추천 가능한 경로가 없습니다.", response));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(createdMessage(response), response));
    }

    @GetMapping("/{recommendationId}")
    public ResponseEntity<ApiResponse<SearchRouteRecommendationResponse>> getRouteRecommendationById(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recommendationId
    ) {
        SearchRouteRecommendationResponse response = routeRecommendationService.findRouteRecommendationById(userId, recommendationId);
        return ResponseEntity.ok(ApiResponse.ok("추천 경로 상세 조회에 성공했습니다.", response));
    }

    @DeleteMapping("/{recommendationId}")
    public ResponseEntity<ApiResponse<Void>> deleteRouteRecommendation(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recommendationId
    ) {
        routeRecommendationService.deleteRouteRecommendation(userId, recommendationId);
        return ResponseEntity.ok(ApiResponse.<Void>ok("추천 경로 이력이 삭제되었습니다.", null));
    }

    private String createdMessage(SearchRouteRecommendationResponse response) {
        return "선택하신 %d개의 어트랙션 중 %d개를 방문할 수 있는 경로를 추천했습니다."
                .formatted(response.totalSelectedAttractions(), response.totalRecommendedAttractions());
    }
}