package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Location;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.ExcludedAttraction;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.Visit;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.service.RouteRecommendationService;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.config.SecurityConfig;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RouteRecommendationController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationEntryPoint.class,
        JwtAccessDeniedHandler.class
})
@EnableWebSecurity
class RouteRecommendationControllerTest {

    private static final String URL = "/api/route-recommendations";
    private static final Long USER_ID = 1L;
    private static final LocalDate VISIT_DATE = LocalDate.of(2025, 11, 6);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteRecommendationService routeRecommendationService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private static final String VALID_BODY = """
            {
              "ticketId": 10,
              "attractionIds": [1, 2, 3, 4, 5],
              "latitude": 37.2930, "longitude": 127.2010, "accuracy": 30,
              "altitude": 45.2, "altitudeAccuracy": 10, "heading": 180, "speed": 1.5,
              "timestamp": 1762412345000
            }
            """;

    // ===== 추천 생성 =====

    @Test
    @DisplayName("추천 경로가 있으면 HTTP 201 과 선택·방문 수가 담긴 메시지를 반환한다")
    void createRouteRecommendation_Full_Returns201() throws Exception {
        // given
        when(routeRecommendationService.createRouteRecommendation(eq(USER_ID), any()))
                .thenReturn(response(fullRecommendation()));

        // when
        ResultActions result = postWithMember(VALID_BODY);

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("선택하신 1개의 어트랙션 중 1개를 방문할 수 있는 경로를 추천했습니다."))
                .andExpect(jsonPath("$.data.recommendation.recommendationId").value(100))
                .andExpect(jsonPath("$.data.recommendation.resultType").value("FULL"))
                .andExpect(jsonPath("$.data.recommendation.estimatedValues").value(true))
                .andExpect(jsonPath("$.data.recommendation.visits[0].attractionName").value("범퍼카"))
                .andExpect(jsonPath("$.data.recommendation.visits[0].moveEstimated").value(true))
                .andExpect(jsonPath("$.data.location.latitude").value(37.2930))
                .andExpect(jsonPath("$.data.location.warnings").isEmpty());

        verify(routeRecommendationService).createRouteRecommendation(USER_ID, new CreateRouteRecommendationRequest(
                10L, List.of(1L, 2L, 3L, 4L, 5L), 37.2930, 127.2010, 30.0, 45.2, 10.0, 180.0, 1.5,
                1762412345000L));
    }

    @Test
    @DisplayName("추천 가능한 경로가 없으면(NONE) HTTP 200 과 어트랙션별 제외 사유를 반환한다")
    void createRouteRecommendation_None_Returns200() throws Exception {
        // given
        SearchRouteRecommendationResponse none = SearchRouteRecommendationResponse.none(
                10L, VISIT_DATE.atTime(21, 50), VISIT_DATE.atTime(22, 0), 1,
                List.of(ExcludedAttraction.of(1L, "범퍼카", ExclusionReason.OUT_OF_TIME_RANGE)));
        when(routeRecommendationService.createRouteRecommendation(eq(USER_ID), any())).thenReturn(response(none));

        // when
        ResultActions result = postWithMember(VALID_BODY);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("선택한 조건에서 추천 가능한 경로가 없습니다."))
                .andExpect(jsonPath("$.data.recommendation.resultType").value("NONE"))
                .andExpect(jsonPath("$.data.recommendation.recommendationId").doesNotExist())
                .andExpect(jsonPath("$.data.recommendation.excludedAttractions[0].reasonCode")
                        .value("OUT_OF_TIME_RANGE"));
    }

    @Test
    @DisplayName("어트랙션을 하나도 고르지 않으면 HTTP 400")
    void createRouteRecommendation_NoAttractions_Returns400() throws Exception {
        // given
        String body = """
                {"ticketId": 10, "attractionIds": [], "latitude": 37.2930, "longitude": 127.2010, "accuracy": 30}
                """;

        // when
        ResultActions result = postWithMember(body);

        // then
        expectFieldError(result, "attractionIds");
    }

    @Test
    @DisplayName("어트랙션을 6개 고르면 HTTP 400")
    void createRouteRecommendation_SixAttractions_Returns400() throws Exception {
        // given
        String body = """
                {"ticketId": 10, "attractionIds": [1, 2, 3, 4, 5, 6],
                 "latitude": 37.2930, "longitude": 127.2010, "accuracy": 30}
                """;

        // when
        ResultActions result = postWithMember(body);

        // then
        expectFieldError(result, "attractionIds");
    }

    @Test
    @DisplayName("입장권 ID가 없으면 HTTP 400")
    void createRouteRecommendation_MissingTicketId_Returns400() throws Exception {
        // given
        String body = """
                {"attractionIds": [1, 2], "latitude": 37.2930, "longitude": 127.2010, "accuracy": 30}
                """;

        // when
        ResultActions result = postWithMember(body);

        // then
        expectFieldError(result, "ticketId");
    }

    @Test
    @DisplayName("위치 정확도가 없으면 HTTP 400 (빼고 보내는 것으로 정확도 검사를 우회할 수 없다)")
    void createRouteRecommendation_MissingAccuracy_Returns400() throws Exception {
        // given
        String body = """
                {"ticketId": 10, "attractionIds": [1, 2], "latitude": 37.2930, "longitude": 127.2010}
                """;

        // when
        ResultActions result = postWithMember(body);

        // then
        expectFieldError(result, "accuracy");
    }

    // 요청 본문의 각 필드를 하나씩 잘못 넣고, 어느 필드에서 400 이 나는지 확인한다.
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "입장권 ID 0          | ticketId         | \"ticketId\": 0, \"attractionIds\": [1], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30",
            "위도 누락            | latitude         | \"ticketId\": 10, \"attractionIds\": [1], \"longitude\": 127.2010, \"accuracy\": 30",
            "경도 누락            | longitude        | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 37.2930, \"accuracy\": 30",
            "위도 90 초과          | latitude         | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 90.0001, \"longitude\": 127.2010, \"accuracy\": 30",
            "경도 -180 미만        | longitude        | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 37.2930, \"longitude\": -180.0001, \"accuracy\": 30",
            "정확도 음수           | accuracy         | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": -1",
            "방향 360             | heading          | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"heading\": 360",
            "속도 음수             | speed            | \"ticketId\": 10, \"attractionIds\": [1], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"speed\": -0.1",
            "ID 목록 누락          | attractionIds    | \"ticketId\": 10, \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30",
            "ID 에 null           | attractionIds[1] | \"ticketId\": 10, \"attractionIds\": [1,null], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30",
            "ID 에 0              | attractionIds[0] | \"ticketId\": 10, \"attractionIds\": [0,2], \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30",
    })
    @DisplayName("잘못된 요청 값은 HTTP 400 과 필드별 오류를 반환하고 서비스를 호출하지 않는다")
    void createRouteRecommendation_InvalidField_Returns400(String caseName, String field, String body) throws Exception {
        // given
        String json = "{" + body + "}";

        // when
        ResultActions result = postWithMember(json);

        // then
        expectFieldError(result, field);
    }

    @Test
    @DisplayName("로그인하지 않으면 HTTP 401")
    void createRouteRecommendation_Unauthenticated_Returns401() throws Exception {
        // given
        // when
        ResultActions result = mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(routeRecommendationService);
    }

    @Test
    @DisplayName("서비스의 비즈니스 예외는 ErrorCode 의 HTTP 상태로 반환된다 (이용 예정 예약 → 409)")
    void createRouteRecommendation_BusinessException_MappedByErrorCode() throws Exception {
        // given
        when(routeRecommendationService.createRouteRecommendation(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_BLOCKED));

        // when
        ResultActions result = postWithMember(VALID_BODY);

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ErrorCode.ROUTE_RECOMMENDATION_BLOCKED.getMessage()));
    }

    // ===== 추천 가능한 어트랙션 목록 =====

    @Test
    @DisplayName("추천 가능한 어트랙션 목록을 200으로 반환한다")
    void findRecommendableAttractions_Authenticated_Returns200() throws Exception {
        // given
        when(routeRecommendationService.findRecommendableAttractions()).thenReturn(List.of(
                new RecommendableAttractionResponse(1L, "범퍼카", 37.2923658, 127.1995663),
                new RecommendableAttractionResponse(6L, "허리케인", 37.29397, 127.20161)));

        // when
        ResultActions result = mockMvc.perform(get(URL + "/attractions").with(authenticatedMember()));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attractionId").value(1))
                .andExpect(jsonPath("$.data[1].attractionName").value("허리케인"))
                // 지도 마커용 좌표가 응답에 들어간다
                .andExpect(jsonPath("$.data[0].latitude").value(37.2923658))
                .andExpect(jsonPath("$.data[0].longitude").value(127.1995663));
    }

    @Test
    @DisplayName("로그인하지 않으면 추천 가능한 어트랙션 목록도 401")
    void findRecommendableAttractions_Unauthenticated_Returns401() throws Exception {
        // given
        // when
        ResultActions result = mockMvc.perform(get(URL + "/attractions"));

        // then
        result.andExpect(status().isUnauthorized());
        verifyNoInteractions(routeRecommendationService);
    }

    // ===== 테스트 도우미 =====

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private ResultActions postWithMember(String body) throws Exception {
        return mockMvc.perform(post(URL).with(authenticatedMember())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void expectFieldError(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data['" + field + "']").exists());
        verifyNoInteractions(routeRecommendationService);
    }

    private CreateRouteRecommendationResponse response(SearchRouteRecommendationResponse recommendation) {
        return CreateRouteRecommendationResponse.of(recommendation,
                new Location(37.2930, 127.2010, 30.0, 45.2, 10.0, 180.0, 1.5, null, List.of()));
    }

    private SearchRouteRecommendationResponse fullRecommendation() {
        LocalDateTime startAt = VISIT_DATE.atTime(12, 0);
        Visit visit = new Visit(1, 1L, "범퍼카", 1001L, 5, true, startAt.plusMinutes(5), 25,
                VISIT_DATE.atTime(12, 30), VISIT_DATE.atTime(12, 35), 5);
        return new SearchRouteRecommendationResponse(
                100L, 10L, startAt, VISIT_DATE.atTime(22, 0), RouteResultType.FULL,
                1, 1, 5, 25, 5, 35, VISIT_DATE.atTime(12, 35), startAt, true,
                List.of(visit), List.of());
    }
}