package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationSummaryResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.response.PageableDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.mockito.Mockito.doThrow;

@WebMvcTest(RouteRecommendationController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
public class RouteRecommendationControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteRecommendationService routeRecommendationService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
    }

    private SearchRouteRecommendationResponse detailResponse() {
        return new SearchRouteRecommendationResponse(
                1L, 5001L, at("13:00"), at("18:00"),
                StartLocation.ENTRANCE, TravelTimeBasis.ENTRANCE_FIXED, RouteResultType.PARTIAL,
                2, 1, 10, 20, 20, 50, at("13:50"), at("12:00"),
                List.of(new SearchRouteRecommendationResponse.Visit(
                        1, 102L, "사파리", 9201L, 10, at("13:10"), 20, at("13:30"), at("13:50"), 20)),
                List.of(new SearchRouteRecommendationResponse.ExcludedAttraction(
                        104L, "어트랙션 D", "SLOT_FULL", "예약 가능한 슬롯이 모두 매진되었습니다."))
        );
    }

    @Test
    @DisplayName("인증된 회원의 추천 경로 상세를 HTTP 200으로 반환한다")
    void getRouteRecommendationById_authenticated_returnsOk() throws Exception {
        // given
        when(routeRecommendationService.findRouteRecommendationById(1L, 1L)).thenReturn(detailResponse());

        // when & then
        mockMvc.perform(get("/api/route-recommendations/1")
                        .with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("추천 경로 상세 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.recommendationId").value(1))
                .andExpect(jsonPath("$.data.startAt").value("2026-10-20T13:00:00"))
                .andExpect(jsonPath("$.data.startLocation").value("ENTRANCE"))
                .andExpect(jsonPath("$.data.travelTimeBasis").value("ENTRANCE_FIXED"))
                .andExpect(jsonPath("$.data.resultType").value("PARTIAL"))
                .andExpect(jsonPath("$.data.totalRecommendedAttractions").value(1))
                .andExpect(jsonPath("$.data.visits.length()").value(1))
                .andExpect(jsonPath("$.data.visits[0].attractionName").value("사파리"))
                .andExpect(jsonPath("$.data.visits[0].estimatedArrivalAt").value("2026-10-20T13:10:00"))
                .andExpect(jsonPath("$.data.excludedAttractions[0].reasonCode").value("SLOT_FULL"));

        verify(routeRecommendationService).findRouteRecommendationById(1L, 1L);
    }

    @Test
    @DisplayName("인증 없이 추천 경로 상세를 조회하면 HTTP 401을 반환한다")
    void getRouteRecommendationById_unauthenticated_returnsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(get("/api/route-recommendations/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(routeRecommendationService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ROUTE_RECOMMENDATION_NOT_FOUND", "NOT_ROUTE_OWNER"})
    @DisplayName("추천 미존재와 타인 추천 접근을 각각 HTTP 404와 403으로 반환한다")
    void getRouteRecommendationById_rejected_returnsExpectedError(ErrorCode errorCode) throws Exception {
        // given
        when(routeRecommendationService.findRouteRecommendationById(1L, 1L))
                .thenThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(get("/api/route-recommendations/1")
                        .with(authenticatedMember()))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    @Test
    @DisplayName("추천 경로 ID 가 숫자가 아니면 HTTP 400을 반환한다")
    void getRouteRecommendationById_nonNumericId_returnsBadRequest() throws Exception {
        // when & then
        mockMvc.perform(get("/api/route-recommendations/abc")
                        .with(authenticatedMember()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(routeRecommendationService);
    }



    //목록 조회
    private PageableDto<SearchRouteRecommendationSummaryResponse> summaryPage() {
        SearchRouteRecommendationSummaryResponse summary = new SearchRouteRecommendationSummaryResponse(
                1L, 5001L, at("13:00"), at("18:00"),
                StartLocation.ENTRANCE, TravelTimeBasis.ENTRANCE_FIXED, RouteResultType.PARTIAL,
                5, 3, 37, 83, 40, 160, at("15:40"), at("12:00"));
        return new PageableDto<>(List.of(summary), 0, 10, 1, 1, false);
    }

    @Test
    @DisplayName("인증된 회원의 추천 경로 목록을 HTTP 200으로 반환한다")
    void getRouteRecommendationAll_authenticated_returnsOk() throws Exception {
        // given
        when(routeRecommendationService.findRouteRecommendations(1L, 0, 10)).thenReturn(summaryPage());

        // when & then
        mockMvc.perform(get("/api/route-recommendations")
                        .param("page", "0")
                        .param("size", "10")
                        .with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("추천 경로 목록 조회에 성공했습니다."))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].recommendationId").value(1))
                .andExpect(jsonPath("$.data.content[0].totalRecommendedAttractions").value(3))
                .andExpect(jsonPath("$.data.content[0].createdAt").value("2026-10-20T12:00:00"))
                .andExpect(jsonPath("$.data.content[0].visits").doesNotExist())   // 목록에는 방문 일정 없음
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("page, size 를 생략하면 0페이지 10개로 조회한다")
    void getRouteRecommendationAll_noParams_usesDefaults() throws Exception {
        // given
        when(routeRecommendationService.findRouteRecommendations(1L, 0, 10)).thenReturn(summaryPage());

        // when & then
        mockMvc.perform(get("/api/route-recommendations")
                        .with(authenticatedMember()))
                .andExpect(status().isOk());

        verify(routeRecommendationService).findRouteRecommendations(1L, 0, 10);
    }

    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, 101", "size, abc"})
    @DisplayName("page 가 0 미만이거나 size 가 1~100 밖이거나 숫자가 아니면 HTTP 400을 반환한다")
    void getRouteRecommendationAll_invalidPaging_returnsBadRequest(String name, String value) throws Exception {
        // when & then
        mockMvc.perform(get("/api/route-recommendations")
                        .param(name, value)
                        .with(authenticatedMember()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(routeRecommendationService);
    }

    @Test
    @DisplayName("인증 없이 추천 경로 목록을 조회하면 HTTP 401을 반환한다")
    void getRouteRecommendationAll_unauthenticated_returnsUnauthorized() throws Exception {
        // when & then
        mockMvc.perform(get("/api/route-recommendations"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(routeRecommendationService);
    }

    //삭제
    @Test
    @DisplayName("인증된 회원의 추천 경로 이력을 삭제하고 HTTP 200을 반환한다")
    void deleteRouteRecommendation_authenticated_returnsOk() throws Exception {
        // when & then
        mockMvc.perform(delete("/api/route-recommendations/1")
                        .with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("추천 경로 이력이 삭제되었습니다."));

        verify(routeRecommendationService).deleteRouteRecommendation(1L, 1L);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ROUTE_RECOMMENDATION_NOT_FOUND", "NOT_ROUTE_OWNER"})
    @DisplayName("추천 미존재와 타인 추천 삭제를 각각 HTTP 404와 403으로 반환한다")
    void deleteRouteRecommendation_rejected_returnsExpectedError(ErrorCode errorCode) throws Exception {
        // given
        doThrow(new BusinessException(errorCode))
                .when(routeRecommendationService).deleteRouteRecommendation(1L, 1L);

        // when & then
        mockMvc.perform(delete("/api/route-recommendations/1")
                        .with(authenticatedMember()))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    @Test
    @DisplayName("인증 없이 추천 경로 이력을 삭제하면 HTTP 401을 반환한다")
    void deleteRouteRecommendation_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/route-recommendations/1"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(routeRecommendationService);
    }

}
