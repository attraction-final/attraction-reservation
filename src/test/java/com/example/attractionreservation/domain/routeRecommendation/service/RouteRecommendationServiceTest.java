package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationSummaryResponse;
import com.example.attractionreservation.domain.routeRecommendation.repository.StopCount;
import com.example.attractionreservation.global.response.PageableDto;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class RouteRecommendationServiceTest {
    @Mock
    private RouteRecommendationRepository routeRecommendationRepository;

    @InjectMocks
    private RouteRecommendationService routeRecommendationService;

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }

    private RouteRecommendation recommendationOwnedBy(Long userId) {
        RouteRecommendation recommendation = RouteRecommendation.create(
                userId,
                5001L,
                at("13:00"),
                at("18:00"),
                List.of(
                        RouteRecommendationStop.of(1, 102L, 9201L, "사파리", 10, at("13:10"), at("13:30"), at("13:50")),
                        RouteRecommendationStop.of(2, 101L, 9001L, "T익스프레스", 15, at("14:05"), at("14:30"), at("14:40"))),
                List.of(RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.SLOT_FULL)));
        ReflectionTestUtils.setField(recommendation, "id", 1L);
        return recommendation;
    }

    @Test
    @DisplayName("본인 추천을 조회하면 방문 순서·시간·제외 사유를 응답으로 변환한다")
    void findRouteRecommendationById_Owner_ReturnsResponse() {
        // given
        when(routeRecommendationRepository.findDetailById(1L)).thenReturn(Optional.of(recommendationOwnedBy(7L)));

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService.findRouteRecommendationById(7L, 1L);

        // then
        assertThat(response.recommendationId()).isEqualTo(1L);
        assertThat(response.ticketId()).isEqualTo(5001L);
        assertThat(response.resultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(response.totalSelectedAttractions()).isEqualTo(3);
        assertThat(response.totalRecommendedAttractions()).isEqualTo(2);
        assertThat(response.visits())
                .extracting(SearchRouteRecommendationResponse.Visit::attractionName)
                .containsExactly("사파리", "T익스프레스");
        assertThat(response.visits().get(0).estimatedWaitMinutes()).isEqualTo(20);
        assertThat(response.excludedAttractions())
                .extracting(SearchRouteRecommendationResponse.ExcludedAttraction::reasonCode)
                .containsExactly("SLOT_FULL");
    }

    @Test
    @DisplayName("없거나 삭제된 추천을 조회하면 ROUTE_RECOMMENDATION_NOT_FOUND 예외가 발생한다")
    void findRouteRecommendationById_NotFound_ThrowsException() {
        // given
        when(routeRecommendationRepository.findDetailById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> routeRecommendationService.findRouteRecommendationById(7L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원의 추천을 조회하면 NOT_ROUTE_OWNER 예외가 발생한다")
    void findRouteRecommendationById_OtherUser_ThrowsException() {
        // given
        when(routeRecommendationRepository.findDetailById(1L)).thenReturn(Optional.of(recommendationOwnedBy(7L)));

        // when & then
        assertThatThrownBy(() -> routeRecommendationService.findRouteRecommendationById(8L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_ROUTE_OWNER);
    }


    //목록 조회
    @Test
    @DisplayName("목록 항목에 집계한 방문지 개수를 담고 페이지 정보를 반환한다")
    void findRouteRecommendations_Recommendations_MapsStopCounts() {
        // given: 추천 2개. 방문지 개수는 엔티티가 아니라 집계 쿼리 결과(3, 1)를 써야 한다
        RouteRecommendation first = recommendationOwnedBy(7L);    // id = 1
        RouteRecommendation second = recommendationOwnedBy(7L);
        ReflectionTestUtils.setField(second, "id", 2L);

        when(routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(first, second), PageRequest.of(0, 10), 2));
        when(routeRecommendationRepository.countStopsByRecommendationIds(List.of(1L, 2L)))
                .thenReturn(List.of(new StopCount(1L, 3L), new StopCount(2L, 1L)));

        // when
        PageableDto<SearchRouteRecommendationSummaryResponse> result =
                routeRecommendationService.findRouteRecommendations(7L, 0, 10);

        // then
        assertThat(result.content())
                .extracting(SearchRouteRecommendationSummaryResponse::totalRecommendedAttractions)
                .containsExactly(3, 1);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("추천이 없으면 빈 목록을 반환하고 방문지 개수 쿼리를 보내지 않는다")
    void findRouteRecommendations_Empty_SkipsStopCountQuery() {
        // given
        when(routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(eq(7L), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        // when
        PageableDto<SearchRouteRecommendationSummaryResponse> result =
                routeRecommendationService.findRouteRecommendations(7L, 0, 10);

        // then
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verify(routeRecommendationRepository, never()).countStopsByRecommendationIds(any());
    }

    @Test
    @DisplayName("최신순(생성 시각 내림차순, 같으면 id 내림차순)과 요청한 페이지로 조회한다")
    void findRouteRecommendations_PassesNewestFirstPageable() {
        // given
        when(routeRecommendationRepository.findAllByUserIdAndDeletedAtIsNull(eq(7L), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(2, 5)));

        // when
        routeRecommendationService.findRouteRecommendations(7L, 2, 5);

        // then: 리포지토리에 실제로 넘어간 Pageable 을 꺼내서 확인한다
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(routeRecommendationRepository).findAllByUserIdAndDeletedAtIsNull(eq(7L), captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
