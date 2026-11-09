package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.GeoPoint;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.PathEdge;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLeg;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.VisitOrderOptimizer;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.VisitOrderResult;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Leg;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Location;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LocationWarning;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.UnavailablePath;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.UnavailableReason;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Visit;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionLocationRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionPathRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 현재 위치(브라우저 Geolocation)와 선택한 어트랙션 5개로 총 이동 시간이 가장 짧은 방문 순서를 추천한다.
 *
 * 처리 순서
 *   1. DB 없이 할 수 있는 검증 (위치 정확도, 중복 ID)
 *   2. 조회 ①②: 어트랙션, 좌표
 *   3. 첫 구간(사용자 위치 → 각 어트랙션) 추정 + 서비스 지역 검증
 *   4. 조회 ③: 선택한 어트랙션끼리의 구간 → 메모리 행렬
 *   5. 완전 탐색 (VisitOrderOptimizer)
 *   6. 응답 조립
 *
 * DB 조회는 방문 순서 수와 관계없이 항상 3번이다. 결과는 저장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RouteRecommendationService {
    private final AttractionRepository attractionRepository;
    private final AttractionLocationRepository attractionLocationRepository;
    private final AttractionPathRepository attractionPathRepository;
    private final StartLegEstimator startLegEstimator;
    private final VisitOrderOptimizer visitOrderOptimizer;
    private final RouteRecommendationProperties properties;
    private final Clock clock;

    // 추천에 고를 수 있는 어트랙션 (삭제되지 않았고 좌표가 있는 어트랙션). 화면이 선택지를 만들 때 쓴다.
    @Transactional(readOnly = true)
    public List<RecommendableAttractionResponse> findRecommendableAttractions() {
        return attractionLocationRepository.findRecommendableAttractions();
    }

    @Transactional(readOnly = true)
    public CreateRouteRecommendationResponse recommend(CreateRouteRecommendationRequest request) {
        List<Long> attractionIds = request.attractionIds();

        // 1. DB 조회 전에 끝낼 수 있는 검증
        validateAccuracy(request.accuracy()); //현재 위치 정확도 체크
        validateNoDuplicateAttraction(attractionIds); //어트랙션 중복 검사

        // 2. 조회 ①②
        Map<Long, Attraction> attractions = findAttractions(attractionIds); //있는 어트랙션만 가져옴
        Map<Long, GeoPoint> locations = findLocations(attractionIds);

        // 3. 첫 구간 추정 (어트랙션 5개 → 5번 계산, 순서 120가지가 이 값을 재사용한다)
        GeoPoint userLocation = new GeoPoint(request.latitude(), request.longitude());
        Map<Long, StartLeg> startLegs = estimateStartLegs(userLocation, attractionIds, locations);
        validateServiceArea(startLegs);

        // 4. 조회 ③ → 메모리 행렬
        List<AttractionPath> paths = attractionPathRepository.findAllBetween(attractionIds);
        Map<Long, Map<Long, PathEdge>> activeEdges = toActiveEdges(paths);
        List<UnavailablePath> unavailablePaths = findUnavailablePaths(attractionIds, paths);

        // 5. 완전 탐색
        VisitOrderResult result = visitOrderOptimizer.optimize(attractionIds, startLegs, activeEdges);
        if (!result.hasValidOrder()) {
            throw new BusinessException(ErrorCode.ROUTE_NO_VALID_ORDER);
        }

        // 6. 응답
        return new CreateRouteRecommendationResponse(
                toLocation(request),
                result.totalDistanceM(),
                result.totalMoveMinutes(),
                result.edges().stream().anyMatch(PathEdge::estimated),
                result.evaluatedOrderCount(),
                result.validOrderCount(),
                toVisits(result.order(), attractions, locations),
                toLegs(result),
                unavailablePaths
        );
    }

    // ===== 검증 =====

    // 정확도(오차 반경)가 기준 이상이면 첫 구간 계산을 믿을 수 없으므로 거부한다.
    private void validateAccuracy(double accuracy) {
        double limit = properties.getAccuracyLimitMeters();
        if (accuracy >= limit) {
            throw new BusinessException(ErrorCode.LOCATION_ACCURACY_TOO_LOW,
                    "현재 위치의 정확도(오차 %.0fm)가 기준(%.0fm 미만)보다 낮아 경로를 추천할 수 없습니다. 위치를 다시 측정해주세요."
                            .formatted(accuracy, limit));
            //%.0f는 "실수(f)를 소수점 0자리로 반올림해서 넣어라
            //문자열 안의 빈칸 %.0f에 실제 숫자를 끼워 넣는 거예요. Java 15부터 생긴 String.format()의 짧은 버전이에요.
        }
    }

    private void validateNoDuplicateAttraction(List<Long> attractionIds) {
        if (new HashSet<>(attractionIds).size() != attractionIds.size()) { //어트랙션 중복 검사
            throw new BusinessException(ErrorCode.DUPLICATE_ROUTE_ATTRACTION);
        }
    }

    // 선택한 어트랙션 중 가장 가까운 곳까지의 직선거리가 기준을 넘으면 공원 밖으로 본다.
    private void validateServiceArea(Map<Long, StartLeg> startLegs) {
        int nearestMeters = startLegs.values().stream()
                .mapToInt(StartLeg::straightDistanceM)
                .min()
                .orElseThrow();
        if (nearestMeters > properties.getMaxStartDistanceMeters()) {
            throw new BusinessException(ErrorCode.LOCATION_OUT_OF_SERVICE_AREA,
                    "가장 가까운 어트랙션까지 직선거리 %dm로, 서비스 기준(%dm 이내)을 벗어났습니다."
                            .formatted(nearestMeters, properties.getMaxStartDistanceMeters()));
        }
    }

    // ===== 조회 =====

    // 조회 ①: 삭제되지 않은 어트랙션만 인정한다. 하나라도 없으면 없는 ID를 알려준다.
    private Map<Long, Attraction> findAttractions(List<Long> attractionIds) {
        Map<Long, Attraction> byId = attractionRepository.findAllById(attractionIds).stream()
                .filter(attraction -> attraction.getDeletedAt() == null)
                .collect(Collectors.toMap(Attraction::getId, Function.identity()));

        List<Long> missingIds = attractionIds.stream()
                .filter(id -> !byId.containsKey(id)) //.containsKey ->(있다/없다만 답함)
                .toList();
        if (!missingIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND,
                    "어트랙션을 찾을 수 없습니다. attractionIds=" + missingIds);
        }
        return byId;
    }

    // 조회 ②: 좌표가 없으면 첫 구간도, 응답 좌표도 만들 수 없으므로 거부한다.
    private Map<Long, GeoPoint> findLocations(List<Long> attractionIds) {
        Map<Long, GeoPoint> byId = attractionLocationRepository.findAllByAttractionIdIn(attractionIds).stream()
                .collect(Collectors.toMap(
                        AttractionLocation::getAttractionId,
                        location -> new GeoPoint(location.getLatitude(), location.getLongitude())));

        List<Long> missingIds = attractionIds.stream()
                .filter(id -> !byId.containsKey(id))
                .toList();
        if (!missingIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ATTRACTION_LOCATION_NOT_FOUND,
                    "좌표 정보가 등록되지 않은 어트랙션이 있습니다. attractionIds=" + missingIds);
        }
        return byId;
    }

    // ===== 계산 준비 =====
//사용자 위치에서 각 어트랙션까지 첫 이동이 얼마나 걸리는지
    private Map<Long, StartLeg> estimateStartLegs(
            GeoPoint userLocation, List<Long> attractionIds, Map<Long, GeoPoint> locations) {
        Map<Long, StartLeg> startLegs = new LinkedHashMap<>();
        for (Long attractionId : attractionIds) {
            startLegs.put(attractionId, startLegEstimator.estimate(userLocation, locations.get(attractionId)));
        }
        return startLegs;
    }

    // 활성 구간만 출발 → (도착 → 구간) 행렬로 만든다. 비활성 구간은 계산에 넣지 않는다.
    private Map<Long, Map<Long, PathEdge>> toActiveEdges(List<AttractionPath> paths) {
        Map<Long, Map<Long, PathEdge>> edges = new HashMap<>();
        for (AttractionPath path : paths) {
            if (!path.isActive()) {
                continue;
            }
            edges.computeIfAbsent(path.getFromAttractionId(), key -> new HashMap<>())
                    .put(path.getToAttractionId(), new PathEdge(
                            path.getFromAttractionId(),
                            path.getToAttractionId(),
                            path.getDistanceM(),
                            path.getMoveMinutes(),
                            path.isEstimated()));
        }
        return edges;
    }

    // 선택한 어트랙션의 모든 (출발, 도착) 쌍 중 계산에 쓸 수 없는 구간을 요청 순서대로 모은다.
    private List<UnavailablePath> findUnavailablePaths(List<Long> attractionIds, List<AttractionPath> paths) {
        Map<Long, Map<Long, AttractionPath>> byFromTo = new HashMap<>();
        for (AttractionPath path : paths) {
            byFromTo.computeIfAbsent(path.getFromAttractionId(), key -> new HashMap<>())
                    .put(path.getToAttractionId(), path);
        }

        List<UnavailablePath> unavailable = new ArrayList<>();
        for (Long from : attractionIds) {
            for (Long to : attractionIds) {
                if (from.equals(to)) {
                    continue;
                }
                AttractionPath path = byFromTo.getOrDefault(from, Map.of()).get(to);
                if (path == null) {
                    unavailable.add(new UnavailablePath(from, to, UnavailableReason.MISSING));
                } else if (!path.isActive()) {
                    unavailable.add(new UnavailablePath(from, to, UnavailableReason.INACTIVE));
                }
            }
        }
        return unavailable;
    }

    // ===== 응답 조립 =====

    private Location toLocation(CreateRouteRecommendationRequest request) {
        Instant measuredAt = request.timestamp() == null ? null : Instant.ofEpochMilli(request.timestamp());

        List<LocationWarning> warnings = new ArrayList<>();
        if (measuredAt != null
                && Duration.between(measuredAt, clock.instant()).compareTo(properties.getStaleAfter()) > 0) {
            warnings.add(LocationWarning.STALE_POSITION);
        }

        return new Location(
                request.latitude(),
                request.longitude(),
                request.accuracy(),
                request.altitude(),
                request.altitudeAccuracy(),
                request.heading(),
                request.speed(),
                measuredAt == null ? null : KstTime.toLocalDateTime(measuredAt),
                List.copyOf(warnings)
        );
    }

    private List<Visit> toVisits(List<Long> order, Map<Long, Attraction> attractions, Map<Long, GeoPoint> locations) {
        List<Visit> visits = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            Long attractionId = order.get(i);
            GeoPoint location = locations.get(attractionId);
            visits.add(new Visit(
                    i + 1,
                    attractionId,
                    attractions.get(attractionId).getName(),
                    location.latitude(),
                    location.longitude()));
        }
        return visits;
    }

    // legs[0] = 사용자 위치 → 첫 어트랙션, legs[i] = (i)번째 → (i+1)번째 어트랙션
    private List<Leg> toLegs(VisitOrderResult result) {
        List<Leg> legs = new ArrayList<>();
        StartLeg startLeg = result.startLeg();
        legs.add(Leg.fromUserLocation(
                result.order().get(0),
                startLeg.straightDistanceM(),
                startLeg.distanceM(),
                startLeg.moveMinutes()));

        for (int i = 0; i < result.edges().size(); i++) {
            PathEdge edge = result.edges().get(i);
            legs.add(Leg.fromAttractionPath(
                    i + 2,
                    edge.fromAttractionId(),
                    edge.toAttractionId(),
                    edge.distanceM(),
                    edge.moveMinutes(),
                    edge.estimated()));
        }
        return legs;
    }
}
