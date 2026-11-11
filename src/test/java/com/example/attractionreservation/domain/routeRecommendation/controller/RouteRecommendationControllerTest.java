package com.example.attractionreservation.domain.routeRecommendation.controller;

import com.example.attractionreservation.domain.auth.jwt.JwtProvider;
import com.example.attractionreservation.domain.auth.security.JwtAccessDeniedHandler;
import com.example.attractionreservation.domain.auth.security.JwtAuthenticationEntryPoint;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Leg;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Location;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Visit;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RouteRecommendationService routeRecommendationService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private UserRepository userRepository;

    private RequestPostProcessor authenticatedMember() {
        return authentication(new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static final String VALID_BODY = """
            {
              "latitude": 37.2930, "longitude": 127.2010, "accuracy": 30,
              "altitude": 45.2, "altitudeAccuracy": 10, "heading": 180, "speed": 1.5,
              "timestamp": 1762412345000,
              "attractionIds": [1, 2, 3, 4, 5]
            }
            """;

    private CreateRouteRecommendationResponse response() {
        return new CreateRouteRecommendationResponse(
                new Location(37.2930, 127.2010, 30.0, 45.2, 10.0, 180.0, 1.5, null, List.of()),
                1478, 28, false, 120, 120,
                List.of(new Visit(1, 1L, "범퍼카", 37.2923658, 127.1995663)),
                List.of(Leg.fromUserLocation(1L, 145, 244, 5)),
                List.of());
    }

    @Test
    @DisplayName("로그인한 회원이 올바른 요청을 보내면 HTTP 200 과 추천 결과를 반환한다")
    void recommend_valid_returnsOk() throws Exception {
        when(routeRecommendationService.recommend(any())).thenReturn(response());

        mockMvc.perform(post(URL).with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("최적 방문 순서를 추천했습니다."))
                .andExpect(jsonPath("$.data.totalMoveMinutes").value(28))
                .andExpect(jsonPath("$.data.legs[0].fromType").value("USER_LOCATION"))
                .andExpect(jsonPath("$.data.legs[0].straightDistanceM").value(145))
                .andExpect(jsonPath("$.data.legs[0].basis").value("HAVERSINE_ESTIMATE"))
                .andExpect(jsonPath("$.data.visits[0].attractionName").value("범퍼카"));

        verify(routeRecommendationService).recommend(new CreateRouteRecommendationRequest(
                37.2930, 127.2010, 30.0, 45.2, 10.0, 180.0, 1.5, 1762412345000L, List.of(1L, 2L, 3L, 4L, 5L)));
    }

    @Test
    @DisplayName("로그인하지 않으면 HTTP 401")
    void recommend_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(routeRecommendationService);
    }

    // 요청 본문의 각 필드를 하나씩 잘못 넣고, 어느 필드에서 400 이 나는지 확인한다.
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
            "위도 누락            | latitude         | \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "경도 누락            | longitude        | \"latitude\": 37.2930, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "위도 90 초과          | latitude         | \"latitude\": 90.0001, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "위도 -90 미만         | latitude         | \"latitude\": -90.0001, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "경도 180 초과         | longitude        | \"latitude\": 37.2930, \"longitude\": 180.0001, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "경도 -180 미만        | longitude        | \"latitude\": 37.2930, \"longitude\": -180.0001, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5]",
            "정확도 누락           | accuracy         | \"latitude\": 37.2930, \"longitude\": 127.2010, \"attractionIds\": [1,2,3,4,5]",
            "정확도 음수           | accuracy         | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": -1, \"attractionIds\": [1,2,3,4,5]",
            "방향 360             | heading          | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"heading\": 360, \"attractionIds\": [1,2,3,4,5]",
            "속도 음수             | speed            | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"speed\": -0.1, \"attractionIds\": [1,2,3,4,5]",
            "ID 목록 누락          | attractionIds    | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30",
            "ID 4개               | attractionIds    | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,3,4]",
            "ID 6개               | attractionIds    | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,3,4,5,6]",
            "ID 에 null           | attractionIds[2] | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [1,2,null,4,5]",
            "ID 에 0              | attractionIds[0] | \"latitude\": 37.2930, \"longitude\": 127.2010, \"accuracy\": 30, \"attractionIds\": [0,2,3,4,5]",
    })
    @DisplayName("잘못된 요청 값은 HTTP 400 과 필드별 오류를 반환하고 서비스를 호출하지 않는다")
    void recommend_invalidField_returnsBadRequest(String caseName, String field, String body) throws Exception {
        mockMvc.perform(post(URL).with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + body + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data['" + field + "']").exists());

        verifyNoInteractions(routeRecommendationService);
    }

    @Test
    @DisplayName("경계값 위도 90, 경도 -180, 방향 0, 정확도 0 은 허용한다")
    void recommend_boundaryValues_ok() throws Exception {
        when(routeRecommendationService.recommend(any())).thenReturn(response());

        mockMvc.perform(post(URL).with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"latitude": 90, "longitude": -180, "accuracy": 0, "heading": 0,
                                 "attractionIds": [1,2,3,4,5]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("서비스의 비즈니스 예외는 ErrorCode 의 HTTP 상태로 반환된다 (중복 ID → 400)")
    void recommend_businessException_mappedByErrorCode() throws Exception {
        when(routeRecommendationService.recommend(any()))
                .thenThrow(new BusinessException(ErrorCode.DUPLICATE_ROUTE_ATTRACTION));

        mockMvc.perform(post(URL).with(authenticatedMember())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_ROUTE_ATTRACTION.getMessage()));
    }

    // ===== 추천 가능한 어트랙션 목록 =====

    @Test
    @DisplayName("추천 가능한 어트랙션 목록을 200으로 반환한다")
    void findRecommendableAttractions_returnsOk() throws Exception {
        when(routeRecommendationService.findRecommendableAttractions()).thenReturn(List.of(
                new RecommendableAttractionResponse(1L, "범퍼카", 37.2923658, 127.1995663),
                new RecommendableAttractionResponse(6L, "허리케인", 37.29397, 127.20161)));

        mockMvc.perform(get(URL + "/attractions").with(authenticatedMember()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].attractionId").value(1))
                .andExpect(jsonPath("$.data[1].attractionName").value("허리케인"))
                // 지도 마커용 좌표가 응답에 들어간다
                .andExpect(jsonPath("$.data[0].latitude").value(37.2923658))
                .andExpect(jsonPath("$.data[0].longitude").value(127.1995663));
    }

    @Test
    @DisplayName("로그인하지 않으면 추천 가능한 어트랙션 목록도 401")
    void findRecommendableAttractions_unauthenticated_returnsUnauthorized() throws Exception {
        mockMvc.perform(get(URL + "/attractions"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(routeRecommendationService);
    }
}