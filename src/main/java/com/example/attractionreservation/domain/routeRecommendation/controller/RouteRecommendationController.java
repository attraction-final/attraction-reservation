package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/route-recommendations")
public class RouteRecommendationController {

    private final RouteRecommendationService routeRecommendationService;

    // 추천 결과가 있으면(FULL·PARTIAL) 저장하고 201, 추천 가능한 경로가 없으면(NONE) 저장하지 않고 200.
    @PostMapping
    public ResponseEntity<ApiResponse<CreateRouteRecommendationResponse>> createRouteRecommendation(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateRouteRecommendationRequest request
    ) {
        CreateRouteRecommendationResponse response = routeRecommendationService.createRouteRecommendation(userId, request);
        SearchRouteRecommendationResponse recommendation = response.recommendation();

         if (recommendation.isNone()) {
            return ResponseEntity.ok(ApiResponse.ok("선택한 조건에서 추천 가능한 경로가 없습니다.", response));
        }
        String message = "선택하신 %d개의 어트랙션 중 %d개를 방문할 수 있는 경로를 추천했습니다."
                .formatted(
                        recommendation.totalSelectedAttractions(),
                        recommendation.totalRecommendedAttractions());
       return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(message, response));
    }

    // 추천에 고를 수 있는 어트랙션 목록 (운영 중이고 좌표가 있는 어트랙션). 좌표가 없는 어트랙션은 추천에서 제외되므로 화면이 미리 걸러낸다.
    @GetMapping("/attractions")
    public ResponseEntity<ApiResponse<List<RecommendableAttractionResponse>>> findRecommendableAttractions() {
        return ResponseEntity.ok(ApiResponse.ok(
                "추천 가능한 어트랙션 목록을 조회했습니다.", routeRecommendationService.findRecommendableAttractions()));
    }
}
