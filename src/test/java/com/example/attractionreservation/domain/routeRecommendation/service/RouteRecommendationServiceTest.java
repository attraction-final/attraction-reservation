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
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RouteRecommendationServiceTest {
    @Mock
    private RouteRecommendationRepository routeRecommendationRepository;
    private RouteRecommendationService routeRecommendationService;

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-20T05:00:00Z"), KstTime.ZONE);

    @BeforeEach
    void setUp() {
        routeRecommendationService = new RouteRecommendationService(routeRecommendationRepository, FIXED_CLOCK
        );
    }


        private static LocalDateTime at (String hhmm){
            return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
        }

        private RouteRecommendation recommendationOwnedBy (Long userId){
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
        void findRouteRecommendationById_Owner_ReturnsResponse () {
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
        void findRouteRecommendationById_NotFound_ThrowsException () {
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
        void findRouteRecommendationById_OtherUser_ThrowsException () {
            // given
            when(routeRecommendationRepository.findDetailById(1L)).thenReturn(Optional.of(recommendationOwnedBy(7L)));

            // when & then
            assertThatThrownBy(() -> routeRecommendationService.findRouteRecommendationById(8L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NOT_ROUTE_OWNER);
        }

        //삭제
        @Test
        @DisplayName("본인 추천을 삭제하면 서버 시각으로 삭제 시각이 기록된다")
        void deleteRouteRecommendation_Owner_SoftDeletes () {
            // given
            RouteRecommendation recommendation = recommendationOwnedBy(7L);
            when(routeRecommendationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(recommendation));

            // when
            routeRecommendationService.deleteRouteRecommendation(7L, 1L);

            // then
            assertThat(recommendation.getDeletedAt()).isEqualTo(Instant.parse("2026-10-20T05:00:00Z"));
        }

        @Test
        @DisplayName("없거나 이미 삭제된 추천을 삭제하면 ROUTE_RECOMMENDATION_NOT_FOUND 예외가 발생한다")
        void deleteRouteRecommendation_NotFound_ThrowsException () {
            // given
            when(routeRecommendationRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> routeRecommendationService.deleteRouteRecommendation(7L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 회원의 추천을 삭제하면 NOT_ROUTE_OWNER 예외가 발생하고 삭제되지 않는다")
        void deleteRouteRecommendation_OtherUser_ThrowsExceptionAndKeeps () {
            // given
            RouteRecommendation recommendation = recommendationOwnedBy(7L);
            when(routeRecommendationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(recommendation));

            // when & then
            assertThatThrownBy(() -> routeRecommendationService.deleteRouteRecommendation(8L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.NOT_ROUTE_OWNER);
            assertThat(recommendation.isDeleted()).isFalse();
        }
    }

