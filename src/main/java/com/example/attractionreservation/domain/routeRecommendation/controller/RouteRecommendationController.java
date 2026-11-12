package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.routeRecommendation.dto.*;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteReservationService;
import com.example.attractionreservation.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
@Slf4j

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/route-recommendations")
public class RouteRecommendationController {

    private final RouteRecommendationService routeRecommendationService;
    private final RouteReservationService routeReservationService;

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

    // 저장된 추천 경로의 방문을 한 번에 예약한다. 전부 성공하면 201, 하나라도 실패하면 모두 취소하고 409.
    @PostMapping("/{recommendationId}/reservations")
    public ResponseEntity<ApiResponse<CreateRouteReservationResponse>> createRouteReservations(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recommendationId
    ) {
        CreateRouteReservationResponse response =
                routeReservationService.createRouteReservations(userId, recommendationId);
        // 일괄 예약은 ReservationController를 거치지 않으므로 예약 한 건마다 같은 형식으로 남긴다. 서비스 트랜잭션은 이미 커밋됐다.
        response.reservations().forEach(reservation -> log.info(
                "event=RESERVATION_CREATED reservationId={} userId={} ticketId={} slotId={}",
                reservation.reservationId(),
                userId,
                response.ticketId(),
                reservation.slotId()
        ));
        String message = "추천 경로의 어트랙션 %d개를 예약했습니다.".formatted(response.reservedCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(message, response));
    }

    // 추천에 고를 수 있는 어트랙션 목록 (운영 중이고 좌표가 있는 어트랙션). 좌표가 없는 어트랙션은 추천에서 제외되므로 화면이 미리 걸러낸다.
    @GetMapping("/attractions")
    public ResponseEntity<ApiResponse<List<RecommendableAttractionResponse>>> findRecommendableAttractions() {
        return ResponseEntity.ok(ApiResponse.ok(
                "추천 가능한 어트랙션 목록을 조회했습니다.", routeRecommendationService.findRecommendableAttractions()));
    }
}
