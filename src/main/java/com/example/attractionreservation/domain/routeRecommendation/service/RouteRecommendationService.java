package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RouteRecommendationService {
    private  final RouteRecommendationRepository routeRecommendationRepository;

    @Transactional(readOnly = true)
    public SearchRouteRecommendationResponse findRouteRecommendationById(Long userId, Long recommendationId) {
        RouteRecommendation recommendation = routeRecommendationRepository.findDetailById(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));

        if(!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.NOT_ROUTE_OWNER);
        }
        return SearchRouteRecommendationResponse.from(recommendation);
    }
}
