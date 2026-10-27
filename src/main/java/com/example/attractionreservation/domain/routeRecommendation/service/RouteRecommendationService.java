package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationSummaryResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.StopCount;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteRecommendationService {
    private final RouteRecommendationRepository routeRecommendationRepository;

    @Transactional(readOnly = true)
    public SearchRouteRecommendationResponse findRouteRecommendationById(Long userId, Long recommendationId) {
        RouteRecommendation recommendation = routeRecommendationRepository.findDetailById(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));

        if (!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.NOT_ROUTE_OWNER);
        }
        return SearchRouteRecommendationResponse.from(recommendation);
    }

    @Transactional(readOnly = true)
    public PageableDto<SearchRouteRecommendationSummaryResponse> findRouteRecommendations(
            Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<RouteRecommendation> recommendations =
                routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(userId, pageable);

        Map<Long, Long> stopCounts = countStops(recommendations.getContent());
        return PageableDto.from(recommendations.map(recommendation ->
                SearchRouteRecommendationSummaryResponse.of(
                        recommendation, stopCounts.getOrDefault(recommendation.getId(), 0L).intValue())));
    }
    private Map<Long, Long> countStops(List<RouteRecommendation> recommendations) {
        if (recommendations.isEmpty()) {
            return Map.of();   // 빈 IN 절 쿼리를 보내지 않는다
        }
        List<Long> ids = recommendations.stream()
                .map(RouteRecommendation::getId)
                .toList();
        return routeRecommendationRepository.countStopsByRecommendationIds(ids).stream()
                .collect(Collectors.toMap(StopCount::recommendationId, StopCount::stopCount));
    }
}
