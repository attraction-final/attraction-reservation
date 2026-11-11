package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.AttractionView;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateAttraction;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelection;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelector;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.Exclusion;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.GeoPoint;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.PathEdge;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearchResult;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearcher;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.SlotState;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.SlotView;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLeg;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.TravelTimeTable;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Location;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LocationWarning;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.ExcludedAttraction;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionLocationRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionPathRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 사용자 현재 위치(브라우저 Geolocation)와 선택한 어트랙션(1~5개)으로 방문 순서와 탑승 슬롯을 추천하고 저장한다.
 *
 * 처리 순서
 *   1~2. DB 없이 할 수 있는 검증 (위치 정확도, 중복 ID)
 *   3~4. 입장권(존재·소유자·입장 처리된 IN_USE·방문일 = 오늘), 이용 예정 예약 차단
 *   5.   이용 시작(현재 시각 분 단위 올림)·종료(방문일 폐장) 계산
 *   6~7. 어트랙션 조회(없으면 404), 좌표 조회(없는 어트랙션은 LOCATION_NOT_FOUND 로 제외)
 *   8.   좌표가 있는 어트랙션만 첫 구간(현재 위치 → 어트랙션) 추정 + 서비스 지역 검증
 *   9.   슬롯 조회 → 후보 선정 (CandidateSelector)
 *   10.  후보끼리의 활성 구간 → 경로 탐색 (RouteSearcher)
 *   11.  제외 사유를 요청 순서로 정리 (탐색에서 빠진 후보는 TIME_CONFLICT)
 *   12.  방문이 있으면(FULL·PARTIAL) 저장, 없으면(NONE) 저장하지 않는다.
 *
 * 추천은 예약을 만들거나 슬롯 정원을 바꾸지 않는다 (BR-RTE-30). 위치 좌표는 저장하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RouteRecommendationService {
    private final RouteRecommendationRepository routeRecommendationRepository;
    private final TicketService ticketService;
    private final ReservationRepository reservationRepository;
    private final AttractionRepository attractionRepository;
    private final AttractionSlotRepository attractionSlotRepository;
    private final AttractionLocationRepository attractionLocationRepository;
    private final AttractionPathRepository attractionPathRepository;
    private final CandidateSelector candidateSelector;
    private final RouteSearcher routeSearcher;
    private final StartLegEstimator startLegEstimator;
    private final RouteRecommendationProperties properties;
    private final Clock clock;

    // 추천에 고를 수 있는 어트랙션 (운영 중이고 좌표가 있는 어트랙션). 화면이 선택지를 만들 때 쓴다.
    @Transactional(readOnly = true)
    public List<RecommendableAttractionResponse> findRecommendableAttractions() {
        return attractionLocationRepository.findRecommendableAttractions();
    }

    @Transactional
    public CreateRouteRecommendationResponse createRouteRecommendation(
            Long userId, CreateRouteRecommendationRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> attractionIds = request.attractionIds();

        // 1~2. DB 조회 전에 끝낼 수 있는 검증
        validateAccuracy(request.accuracy());
        validateNoDuplicateAttraction(attractionIds);

        // 3~4. 입장권, 이용 예정 예약
        Ticket ticket = findUsableTicket(userId, request.ticketId(), now.toLocalDate());
        validateNoUpcomingReservation(userId, now);

        // 5. 이용 시간: 사용자는 시작 시각에 요청 위치에서 출발한다고 본다
        LocalDateTime startAt = roundUpToMinute(now);
        LocalDateTime endAt = ticket.getValidDate().atTime(AttractionSlot.PARK_CLOSE_TIME);
        validateTimeRange(startAt, endAt);

        // 6~7. 어트랙션, 좌표
        List<Attraction> attractions = findAttractionsInRequestOrder(attractionIds);
        Map<Long, GeoPoint> locations = findLocations(attractionIds);

        // 8. 첫 구간 추정 + 서비스 지역 검증 (좌표가 있는 어트랙션만)
        GeoPoint userLocation = new GeoPoint(request.latitude(), request.longitude());
        Map<Long, StartLeg> startLegs = estimateStartLegs(userLocation, attractionIds, locations);
        validateServiceArea(startLegs);

        // 9. 후보 선정. 마감(시작 30분 전) 판단은 올리지 않은 현재 시각으로 한다
        List<AttractionView> locatedAttractions = attractions.stream()
                .filter(attraction -> locations.containsKey(attraction.getId()))
                .map(attraction -> new AttractionView(
                        attraction.getId(), attraction.getName(), attraction.isOperating()))
                .toList();
        Map<Long, List<SlotView>> slots = findSlotsByAttraction(locatedAttractions, ticket.getValidDate());
        CandidateSelection selection = candidateSelector.select(locatedAttractions, slots, startAt, endAt, now);

        // 10. 경로 탐색
        TravelTimeTable travelTimes = findActiveTravelTimesBetween(selection.candidates());
        RouteSearchResult route = routeSearcher.search(
                startAt, endAt, selection.candidates(), startLegs, travelTimes);

        // 11. 제외 사유 (요청 순서)
        List<Exclusion> exclusions = collectExclusionsInRequestOrder(attractions, locations, selection, route);

        // 12. 결과
        Location location = toLocation(request);
        if (route.isEmpty()) {
            SearchRouteRecommendationResponse none = SearchRouteRecommendationResponse.none(
                    ticket.getId(), startAt, endAt, attractionIds.size(), toExcludedAttractions(exclusions));
            return CreateRouteRecommendationResponse.of(none, location);
        }

        RouteRecommendation recommendation = RouteRecommendation.create(
                userId, ticket.getId(), startAt, endAt, toStops(route), toExclusionEntities(exclusions));
        RouteRecommendation saved = routeRecommendationRepository.save(recommendation);
        return CreateRouteRecommendationResponse.of(SearchRouteRecommendationResponse.from(saved), location);
    }

    // ===== 검증 =====

    // 정확도(오차 반경)가 기준 이상이면 첫 구간 계산을 믿을 수 없으므로 거부한다.
    private void validateAccuracy(double accuracy) {
        double limit = properties.getAccuracyLimitMeters();
        if (accuracy >= limit) {
            throw new BusinessException(ErrorCode.LOCATION_ACCURACY_TOO_LOW,
                    "현재 위치의 정확도(오차 %.0fm)가 기준(%.0fm 미만)보다 낮아 경로를 추천할 수 없습니다. 위치를 다시 측정해주세요."
                            .formatted(accuracy, limit));
        }
    }

    private void validateNoDuplicateAttraction(List<Long> attractionIds) {
        if (new HashSet<>(attractionIds).size() != attractionIds.size()) {
            throw new BusinessException(ErrorCode.DUPLICATE_ROUTE_ATTRACTION);
        }
    }

    // 본인 소유이고, 놀이공원에 입장 처리되었으며(IN_USE), 방문일이 오늘인 입장권만 쓸 수 있다.
    // IN_USE 는 입장 처리(Ticket.entryTicket)로만 되므로 이것이 입장 여부 확인이다.
    private Ticket findUsableTicket(Long userId, Long ticketId, LocalDate today) {
        Ticket ticket = ticketService.findByTicketId(ticketId);

        Long ownerId = ticket.getOrderItem().getOrder().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }
        if (ticket.getStatus() != TicketStatus.IN_USE) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS);
        }
        if (!ticket.getValidDate().equals(today)) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_DATE_MISMATCH);
        }
        return ticket;
    }

    // 아직 끝나지 않은(슬롯 종료 > 현재) 예약이 하나라도 있으면 추천하지 않는다. 회원 기준이다.
    private void validateNoUpcomingReservation(Long userId, LocalDateTime now) {
        boolean blocked = reservationRepository.existsByUser_IdAndStatusAndAttractionSlot_EndsAtAfter(
                userId, ReservationStatus.RESERVED, now);
        if (blocked) {
            throw new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_BLOCKED);
        }
    }

    private void validateTimeRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (!startAt.isBefore(endAt)) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
    }

    // 선택한 어트랙션 중 가장 가까운 곳까지의 직선거리가 기준을 넘으면 공원 밖으로 본다.
    // 좌표가 있는 어트랙션이 하나도 없으면 비교할 대상이 없으므로 검사하지 않는다 (전부 LOCATION_NOT_FOUND 로 제외된다).
    private void validateServiceArea(Map<Long, StartLeg> startLegs) {
        if (startLegs.isEmpty()) {
            return;
        }
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

    // ===== 시간 계산 =====

    // 10:03:20 → 10:04, 10:03:00 → 10:03 (초·나노초가 남아 있을 때만 올린다)
    private LocalDateTime roundUpToMinute(LocalDateTime now) {
        LocalDateTime truncated = now.truncatedTo(ChronoUnit.MINUTES);
        return truncated.equals(now) ? truncated : truncated.plusMinutes(1);
    }

    // ===== 조회 =====

    // 삭제되지 않은 어트랙션만 인정한다. 하나라도 없으면 없는 ID를 알려준다. 운영 여부는 후보 선정에서 판단한다.
    private List<Attraction> findAttractionsInRequestOrder(List<Long> attractionIds) {
        Map<Long, Attraction> byId = attractionRepository.findAllById(attractionIds).stream()
                .filter(attraction -> attraction.getDeletedAt() == null)
                .collect(Collectors.toMap(Attraction::getId, Function.identity()));

        List<Long> missingIds = attractionIds.stream()
                .filter(id -> !byId.containsKey(id))
                .toList();
        if (!missingIds.isEmpty()) {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND,
                    "어트랙션을 찾을 수 없습니다. attractionIds=" + missingIds);
        }
        return attractionIds.stream().map(byId::get).toList();
    }

    // 좌표가 없는 어트랙션은 결과 맵에 들어오지 않는다 (호출자가 LOCATION_NOT_FOUND 로 제외한다).
    private Map<Long, GeoPoint> findLocations(List<Long> attractionIds) {
        return attractionLocationRepository.findAllByAttractionIdIn(attractionIds).stream()
                .collect(Collectors.toMap(
                        AttractionLocation::getAttractionId,
                        location -> new GeoPoint(location.getLatitude(), location.getLongitude())));
    }

    // 운영 중인 어트랙션만 슬롯을 조회한다. 운영하지 않는 어트랙션은 슬롯과 관계없이 NOT_OPERATING 으로 제외된다.
    private Map<Long, List<SlotView>> findSlotsByAttraction(List<AttractionView> attractions, LocalDate visitDate) {
        LocalDateTime dayStart = visitDate.atStartOfDay();
        LocalDateTime nextDayStart = visitDate.plusDays(1).atStartOfDay();

        Map<Long, List<SlotView>> slotsByAttractionId = new HashMap<>();
        for (AttractionView attraction : attractions) {
            if (!attraction.operating()) {
                continue;
            }
            List<SlotView> slots = attractionSlotRepository
                    .findSlotsByAttractionIdAndDate(attraction.attractionId(), dayStart, nextDayStart, false).stream()
                    .map(slot -> new SlotView(
                            slot.getId(),
                            slot.getStartsAt(),
                            slot.getEndsAt(),
                            SlotState.valueOf(slot.getStatus().name()), // 두 enum 의 상수 이름이 같다
                            slot.getCapacity(),
                            slot.getReservedCount()))
                    .toList();
            slotsByAttractionId.put(attraction.attractionId(), slots);
        }
        return slotsByAttractionId;
    }

    // 후보끼리의 구간을 쿼리 1번으로 읽어 활성 구간만 조회표로 만든다.
    // 비활성 구간은 "데이터 없음"과 똑같이 다룬다: 그 이동이 필요한 방문 순서는 쓰지 않는다 (대체값·역방향 값 없음).
    // 후보가 1개 이하면 어트랙션 간 이동이 없으므로 조회하지 않는다.
    private TravelTimeTable findActiveTravelTimesBetween(List<CandidateAttraction> candidates) {
        if (candidates.size() < 2) {
            return TravelTimeTable.empty();
        }
        List<Long> candidateIds = candidates.stream()
                .map(CandidateAttraction::attractionId)
                .toList();
        List<PathEdge> activeEdges = attractionPathRepository.findAllBetween(candidateIds).stream()
                .filter(AttractionPath::isActive)
                .map(path -> new PathEdge(
                        path.getFromAttractionId(),
                        path.getToAttractionId(),
                        path.getDistanceM(),
                        path.getMoveMinutes(),
                        path.isEstimated()))
                .toList();
        return TravelTimeTable.from(activeEdges);
    }

    // ===== 계산 준비 =====

    // 사용자 위치에서 좌표가 있는 각 어트랙션까지 첫 이동 정보를 추정한다.
    private Map<Long, StartLeg> estimateStartLegs(
            GeoPoint userLocation, List<Long> attractionIds, Map<Long, GeoPoint> locations) {
        Map<Long, StartLeg> startLegs = new LinkedHashMap<>();
        for (Long attractionId : attractionIds) {
            GeoPoint attractionLocation = locations.get(attractionId);
            if (attractionLocation != null) {
                startLegs.put(attractionId, startLegEstimator.estimate(userLocation, attractionLocation));
            }
        }
        return startLegs;
    }

    // ===== 결과 조립 =====

    // 선택한 어트랙션마다 방문 또는 제외 중 정확히 한 곳에 들어가도록 제외 사유를 모아 요청 순서로 정렬한다.
    private List<Exclusion> collectExclusionsInRequestOrder(
            List<Attraction> attractions,
            Map<Long, GeoPoint> locations,
            CandidateSelection selection,
            RouteSearchResult route
    ) {
        Map<Long, String> names = attractions.stream()
                .collect(Collectors.toMap(Attraction::getId, Attraction::getName));

        Map<Long, Exclusion> byId = new HashMap<>();
        for (Attraction attraction : attractions) {
            if (!locations.containsKey(attraction.getId())) {
                byId.put(attraction.getId(), new Exclusion(
                        attraction.getId(), attraction.getName(), ExclusionReason.LOCATION_NOT_FOUND));
            }
        }
        selection.exclusions().forEach(exclusion -> byId.put(exclusion.attractionId(), exclusion));
        route.unreachableAttractionIds().forEach(id ->
                byId.put(id, new Exclusion(id, names.get(id), ExclusionReason.TIME_CONFLICT)));

        return attractions.stream()
                .map(Attraction::getId)
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();
    }

    private List<RouteRecommendationStop> toStops(RouteSearchResult route) {
        return route.visits().stream()
                .map(visit -> RouteRecommendationStop.of(
                        visit.sequence(),
                        visit.attractionId(),
                        visit.slotId(),
                        visit.attractionName(),
                        visit.moveMinutes(),
                        visit.moveEstimated(),
                        visit.arrivalAt(),
                        visit.startsAt(),
                        visit.endsAt()))
                .toList();
    }

    private List<RouteRecommendationExclusion> toExclusionEntities(List<Exclusion> exclusions) {
        return exclusions.stream()
                .map(exclusion -> RouteRecommendationExclusion.of(
                        exclusion.attractionId(), exclusion.attractionName(), exclusion.reason()))
                .toList();
    }

    private List<ExcludedAttraction> toExcludedAttractions(List<Exclusion> exclusions) {
        return exclusions.stream()
                .map(exclusion -> ExcludedAttraction.of(
                        exclusion.attractionId(), exclusion.attractionName(), exclusion.reason()))
                .toList();
    }

    // 요청 위치를 그대로 돌려주고, 측정 시각이 오래되었으면 경고를 붙인다. (응답에만 쓰고 저장하지 않는다)
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
}