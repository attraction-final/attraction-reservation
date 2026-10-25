package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.StartLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.TravelTimeBasis;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

    // 생성

    private static final String VALID_CREATE_BODY = """
            {
              "ticketId": 5001,
              "startAt": "2026-10-20T13:00:00",
              "endAt": "2026-10-20T18:00:00",
              "attractionIds": [101, 102]
            }
            """;

    @Test
    @DisplayName("추천 경로가 저장되면 HTTP 201과 선택·추천 개수 메시지를 반환한다")
    void createRouteRecommendation_saved_returnsCreated() throws Exception {
        // given: detailResponse() = 선택 2개, 추천 1개
        when(routeRecommendationService.createRouteRecommendation(eq(1L), any(CreateRouteRecommendationRequest.class)))
                .thenReturn(detailResponse());

        // when & then
        mockMvc.perform(post("/api/route-recommendations")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("선택하신 2개의 어트랙션 중 1개를 방문할 수 있는 경로를 추천했습니다."))
                .andExpect(jsonPath("$.data.recommendationId").value(1))
                .andExpect(jsonPath("$.data.resultType").value("PARTIAL"));

        // JSON 이 요청 DTO 로 올바르게 바뀌었는지
        ArgumentCaptor<CreateRouteRecommendationRequest> captor =
                ArgumentCaptor.forClass(CreateRouteRecommendationRequest.class);
        verify(routeRecommendationService).createRouteRecommendation(eq(1L), captor.capture());
        CreateRouteRecommendationRequest request = captor.getValue();
        assertThat(request.ticketId()).isEqualTo(5001L);
        assertThat(request.startAt()).isEqualTo(at("13:00"));
        assertThat(request.attractionIds()).containsExactly(101L, 102L);
    }

    @Test
    @DisplayName("추천 가능한 경로가 없으면 HTTP 200과 NONE 결과를 반환한다")
    void createRouteRecommendation_none_returnsOk() throws Exception {
        // given
        SearchRouteRecommendationResponse none = SearchRouteRecommendationResponse.none(
                5001L, at("13:00"), at("18:00"), 1,
                List.of(SearchRouteRecommendationResponse.ExcludedAttraction.of(
                        101L, "T익스프레스", ExclusionReason.RESERVATION_CLOSED)));
        when(routeRecommendationService.createRouteRecommendation(eq(1L), any(CreateRouteRecommendationRequest.class)))
                .thenReturn(none);

        // when & then
        mockMvc.perform(post("/api/route-recommendations")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("선택한 조건에서 추천 가능한 경로가 없습니다."))
                .andExpect(jsonPath("$.data.resultType").value("NONE"))
                .andExpect(jsonPath("$.data.visits.length()").value(0))
                .andExpect(jsonPath("$.data.excludedAttractions[0].reasonCode").value("RESERVATION_CLOSED"));
    }

    static Stream<String> invalidCreateBodies() {
        return Stream.of(
                // ticketId 누락
                """
                {"startAt": "2026-10-20T13:00:00", "endAt": "2026-10-20T18:00:00", "attractionIds": [101]}
                """,
                // 어트랙션 0개
                """
                {"ticketId": 5001, "startAt": "2026-10-20T13:00:00", "endAt": "2026-10-20T18:00:00", "attractionIds": []}
                """,
                // 어트랙션 6개
                """
                {"ticketId": 5001, "startAt": "2026-10-20T13:00:00", "endAt": "2026-10-20T18:00:00", "attractionIds": [1, 2, 3, 4, 5, 6]}
                """,
                // 목록 안에 null
                """
                {"ticketId": 5001, "startAt": "2026-10-20T13:00:00", "endAt": "2026-10-20T18:00:00", "attractionIds": [101, null]}
                """
        );
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    @DisplayName("요청 형식이 잘못되면 서비스를 호출하지 않고 HTTP 400을 반환한다")
    void createRouteRecommendation_invalidBody_returnsBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/route-recommendations")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_INPUT_VALUE.getMessage()));

        verifyNoInteractions(routeRecommendationService);
    }

    @Test
    @DisplayName("시각 형식이 잘못되면 HTTP 400 INVALID_REQUEST_BODY 를 반환한다")
    void createRouteRecommendation_invalidDateFormat_returnsBadRequest() throws Exception {
        String body = """
                {"ticketId": 5001, "startAt": "2026/10/20 13:00", "endAt": "2026-10-20T18:00:00", "attractionIds": [101]}
                """;

        mockMvc.perform(post("/api/route-recommendations")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST_BODY.getMessage()));

        verifyNoInteractions(routeRecommendationService);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "INVALID_ATTRACTION_SELECTION", "INVALID_TIME_RANGE",
            "TICKET_NOT_FOUND", "TICKET_NOT_OWNED", "TICKET_NOT_AVAILABLE",
            "ATTRACTION_NOT_FOUND", "ROUTE_RECOMMENDATION_BLOCKED"})
    @DisplayName("서비스 검증 실패를 ErrorCode 의 HTTP 상태로 반환한다")
    void createRouteRecommendation_rejected_returnsExpectedError(ErrorCode errorCode) throws Exception {
        // given
        when(routeRecommendationService.createRouteRecommendation(eq(1L), any(CreateRouteRecommendationRequest.class)))
                .thenThrow(new BusinessException(errorCode));

        // when & then
        mockMvc.perform(post("/api/route-recommendations")
                        .with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().is(errorCode.getStatus().value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
    }

    @Test
    @DisplayName("인증 없이 추천 경로를 생성하면 HTTP 401을 반환한다")
    void createRouteRecommendation_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/route-recommendations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(routeRecommendationService);
    }
}
