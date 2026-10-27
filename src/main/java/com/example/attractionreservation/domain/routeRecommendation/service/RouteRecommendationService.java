package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.reservation.entity.Reservation;                 // ★ 추가
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;     // ★ 추가
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;   // ★ 추가
import com.example.attractionreservation.domain.routeRecommendation.algorithm.AttractionView;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelection;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelector;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.Exclusion;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearchResult;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearcher;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.SlotState;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.SlotView;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.ExcludedAttraction;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class 정RouteRecommendationService {

    private static final LocalTime AFTERNOON_TICKET_START = LocalTime.of(15, 0);
    private static final long CANCEL_DEADLINE_MINUTES = 30;


    private final RouteRecommendationRepository routeRecommendationRepository;
    private final TicketRepository ticketRepository;
    private final AttractionRepository attractionRepository;
    private final AttractionSlotRepository attractionSlotRepository;
    private final ReservationRepository reservationRepository;
    private final CandidateSelector candidateSelector;
    private final RouteSearcher routeSearcher;
    private final Clock clock;

    @Transactional
    public SearchRouteRecommendationResponse createRouteRecommendation(Long userId, CreateRouteRecommendationRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> attractionIds = request.attractionIds();

        validateNoDuplicateAttraction(attractionIds);
        Ticket ticket = findUsableTicket(request.ticketId(), userId);
        LocalDateTime startAt = correctStartAt(request.startAt(), now);
        LocalDateTime endAt = request.endAt();
        validateTimeRange(startAt, endAt, ticket);
        validateNotBlockedByExistingReservation(ticket.getId(), now);   // ★ 추가

        List<AttractionView> attractions = findAttractionsInRequestOrder(attractionIds);
        Map<Long, List<SlotView>> slotsByAttractionId = findSlotsByAttraction(attractionIds, ticket.getValidDate());

        CandidateSelection selection = candidateSelector.select(attractions, slotsByAttractionId, startAt, endAt, now);
        RouteSearchResult route = routeSearcher.search(startAt, endAt, selection.candidates());
        List<Exclusion> exclusions = collectExclusionsInRequestOrder(attractions, selection, route);

        if (route.isEmpty()) {
            return SearchRouteRecommendationResponse.none(
                    ticket.getId(), startAt, endAt, attractionIds.size(), toExcludedAttractions(exclusions));
        }

        RouteRecommendation recommendation = RouteRecommendation.create(
                userId, ticket.getId(), startAt, endAt, toStops(route), toExclusionEntities(exclusions));
        return SearchRouteRecommendationResponse.from(routeRecommendationRepository.save(recommendation));
    }

    @Transactional(readOnly = true)
    public SearchRouteRecommendationResponse findRouteRecommendationById(Long userId, Long recommendationId) {
        RouteRecommendation recommendation = routeRecommendationRepository.findDetailById(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));

        validateOwner(recommendation, userId);
        return SearchRouteRecommendationResponse.from(recommendation);
    }

    @Transactional
    public void deleteRouteRecommendation(Long userId, Long recommendationId) {
        RouteRecommendation recommendation = routeRecommendationRepository.findByIdAndDeletedAtIsNull(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));

        validateOwner(recommendation, userId);
        recommendation.delete(clock.instant());   // 소프트 삭제, 실제 예약은 그대로
    }

    // 검증

    private void validateNoDuplicateAttraction(List<Long> attractionIds) {
        if (new HashSet<>(attractionIds).size() != attractionIds.size()) {
            throw new BusinessException(ErrorCode.INVALID_ATTRACTION_SELECTION);
        }
    }

    private Ticket findUsableTicket(Long ticketId, Long userId) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));

        Long ownerId = ticket.getOrderItem().getOrder().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new BusinessException(ErrorCode.TICKET_NOT_OWNED);
        }
        if (ticket.getStatus() != TicketStatus.IN_USE) {
            throw new BusinessException(ErrorCode.TICKET_NOT_AVAILABLE);
        }
        return ticket;
    }

    private LocalDateTime correctStartAt(LocalDateTime requestedStartAt, LocalDateTime now) {
        return requestedStartAt.isBefore(now) ? now : requestedStartAt;
    }

    private void validateTimeRange(LocalDateTime startAt, LocalDateTime endAt, Ticket ticket) {
        LocalDate visitDate = ticket.getValidDate();
        LocalDateTime usableFrom = visitDate.atTime(usableStartTime(ticket.getType()));
        LocalDateTime parkClose = visitDate.atTime(AttractionSlot.PARK_CLOSE_TIME);

        boolean invalid = !startAt.isBefore(endAt)
                || startAt.isBefore(usableFrom)
                || endAt.isAfter(parkClose);
        if (invalid) {
            throw new BusinessException(ErrorCode.INVALID_TIME_RANGE);
        }
    }

    private LocalTime usableStartTime(TicketType type) {
        return switch (type) {
            case ALL_DAY -> AttractionSlot.PARK_OPEN_TIME;
            case AFTERNOON -> AFTERNOON_TICKET_START;
        };
    }

    private void validateNotBlockedByExistingReservation(Long ticketId, LocalDateTime now) {
        boolean blocked = reservationRepository
                .findAllWithSlotByTicketIdAndStatus(ticketId, ReservationStatus.RESERVED).stream()
                .map(Reservation::getAttractionSlot)
                .anyMatch(slot -> now.isAfter(slot.getStartsAt().minusMinutes(CANCEL_DEADLINE_MINUTES))
                        && now.isBefore(slot.getEndsAt()));
        if (blocked) {
            throw new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_BLOCKED);
        }
    }

    private void validateOwner(RouteRecommendation recommendation, Long userId) {
        if (!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.NOT_ROUTE_OWNER);
        }
    }

    //조회 + 변환

    private List<AttractionView> findAttractionsInRequestOrder(List<Long> attractionIds) {
        Map<Long, Attraction> byId = attractionRepository.findAllById(attractionIds).stream()
                .filter(attraction -> attraction.getDeletedAt() == null)
                .collect(Collectors.toMap(Attraction::getId, Function.identity()));

        if (byId.size() != attractionIds.size()) {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_FOUND);
        }
        return attractionIds.stream()
                .map(byId::get)
                .map(attraction -> new AttractionView(
                        attraction.getId(),
                        attraction.getName(),
                        attraction.isOperating(),
                        attraction.getEntranceMoveMinutes()))
                .toList();
    }

    private Map<Long, List<SlotView>> findSlotsByAttraction(List<Long> attractionIds, LocalDate visitDate) {
        LocalDateTime dayStart = visitDate.atStartOfDay();
        LocalDateTime nextDayStart = visitDate.plusDays(1).atStartOfDay();

        Map<Long, List<SlotView>> slotsByAttractionId = new HashMap<>();
        for (Long attractionId : attractionIds) {
            List<SlotView> slots = attractionSlotRepository
                    .findSlotsByAttractionIdAndDate(attractionId, dayStart, nextDayStart, false).stream()
                    .map(slot -> new SlotView(
                            slot.getId(),
                            slot.getStartsAt(),
                            slot.getEndsAt(),
                            SlotState.valueOf(slot.getStatus().name()),   // 두 enum 의 상수 이름이 같다
                            slot.getCapacity(),
                            slot.getReservedCount()))
                    .toList();
            slotsByAttractionId.put(attractionId, slots);
        }
        return slotsByAttractionId;
    }

    private List<Exclusion> collectExclusionsInRequestOrder(
            List<AttractionView> attractions, CandidateSelection selection, RouteSearchResult route) {

        Map<Long, Exclusion> byId = new HashMap<>();
        selection.exclusions().forEach(exclusion -> byId.put(exclusion.attractionId(), exclusion));

        Map<Long, String> names = attractions.stream()
                .collect(Collectors.toMap(AttractionView::attractionId, AttractionView::attractionName));
        route.unreachableAttractionIds().forEach(id ->
                byId.put(id, new Exclusion(id, names.get(id), ExclusionReason.TIME_CONFLICT)));

        return attractions.stream()
                .map(AttractionView::attractionId)
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
}