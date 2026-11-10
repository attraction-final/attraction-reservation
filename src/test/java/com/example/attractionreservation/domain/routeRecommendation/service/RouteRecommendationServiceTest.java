package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.routeRecommendation.RouteFixture;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.GeoPoint;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.HaversineStartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.VisitOrderOptimizer;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Leg;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LegBasis;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LegFromType;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LocationWarning;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.UnavailablePath;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.UnavailableReason;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.Visit;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionLocationRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionPathRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.AMAZON;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.BUMPER_CAR;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.EXAMPLE_FIVE;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.HURRICANE;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.MONIMO;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.SPACE_FIGHTER;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.THUNDER_FALLS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 저장소(Repository)만 Mock 으로 두고, 알고리즘(VisitOrderOptimizer)과 첫 구간 추정(HaversineStartLegEstimator)은
 * 실제 객체를 써서 "서비스 + 알고리즘" 흐름 전체를 검증한다.
 * 기대값은 docs/sql 실측 데이터로 Python 에서 따로 계산한 값이다.
 */
@ExtendWith(MockitoExtension.class)
class RouteRecommendationServiceTest {

    // 2025-11-06 12:00:00 KST
    private static final Instant NOW = Instant.parse("2025-11-06T03:00:00Z");

    @Mock
    private AttractionRepository attractionRepository;
    @Mock
    private AttractionLocationRepository attractionLocationRepository;
    @Mock
    private AttractionPathRepository attractionPathRepository;

    private RouteRecommendationService service;

    @BeforeEach
    void setUp() {
        RouteRecommendationProperties properties = new RouteRecommendationProperties();
        service = new RouteRecommendationService(
                attractionRepository,
                attractionLocationRepository,
                attractionPathRepository,
                new HaversineStartLegEstimator(properties),
                new VisitOrderOptimizer(),
                properties,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    // ===== 추천 가능한 어트랙션 목록 =====

    @Test
    @DisplayName("추천 가능한 어트랙션 목록은 좌표 저장소의 조회 결과를 그대로 돌려준다")
    void findRecommendableAttractions_returnsRepositoryResult() {
        List<RecommendableAttractionResponse> expected = List.of(new RecommendableAttractionResponse(1L, "범퍼카"));
        when(attractionLocationRepository.findRecommendableAttractions()).thenReturn(expected);

        assertThat(service.findRecommendableAttractions()).isEqualTo(expected);
        verifyNoInteractions(attractionRepository, attractionPathRepository);
    }

    // ===== 정상 =====

    @Test
    @DisplayName("1. 정상: 예시 5개 → 범퍼카→썬더폴스→우주전투기→모니모→아마존, 28분 1478m, DB 조회 3번")
    void recommend_exampleFive() {
        givenData(EXAMPLE_FIVE, path -> true);

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE));

        assertThat(response.visits()).extracting(Visit::attractionId)
                .containsExactly(BUMPER_CAR, THUNDER_FALLS, SPACE_FIGHTER, MONIMO, AMAZON);
        assertThat(response.visits()).extracting(Visit::attractionName)
                .containsExactly("범퍼카", "썬더폴스", "우주전투기", "모니모 러쉬", "아마존 익스프레스");
        assertThat(response.visits().get(0).latitude()).isEqualTo(37.2923658);
        assertThat(response.totalMoveMinutes()).isEqualTo(28);
        assertThat(response.totalDistanceM()).isEqualTo(1478);
        assertThat(response.evaluatedOrderCount()).isEqualTo(120);
        assertThat(response.validOrderCount()).isEqualTo(120);
        assertThat(response.unavailablePaths()).isEmpty();
        assertThat(response.estimatedPathIncluded()).isFalse();

        // 첫 구간: 사용자 위치 → 범퍼카 (직선 145m, 추정 도보 244m, 5분)
        assertThat(response.legs()).hasSize(5);
        assertThat(response.legs().get(0)).isEqualTo(new Leg(
                1, LegFromType.USER_LOCATION, null, BUMPER_CAR, 145, 244, 5, true, LegBasis.HAVERSINE_ESTIMATE));
        // 두 번째 구간: 범퍼카 → 썬더폴스 실측 141m, 3분
        assertThat(response.legs().get(1)).isEqualTo(new Leg(
                2, LegFromType.ATTRACTION, BUMPER_CAR, THUNDER_FALLS, null, 141, 3, false, LegBasis.ATTRACTION_PATH));
        // 합계 = 구간 합
        assertThat(response.legs().stream().mapToInt(Leg::moveMinutes).sum()).isEqualTo(response.totalMoveMinutes());
        assertThat(response.legs().stream().mapToInt(Leg::distanceM).sum()).isEqualTo(response.totalDistanceM());

        // 120가지를 계산해도 DB 조회는 각 1번
        verify(attractionRepository, times(1)).findAllById(anyIterable());
        verify(attractionLocationRepository, times(1)).findAllByAttractionIdIn(anyCollection());
        verify(attractionPathRepository, times(1)).findAllBetween(anyCollection());
    }

    @Test
    @DisplayName("2. 사용자 위치가 바뀌면 순서가 바뀐다: 아마존 근처 → 아마존→모니모→우주전투기→범퍼카→썬더폴스, 25분")
    void recommend_userLocationChanged() {
        givenData(EXAMPLE_FIVE, path -> true);

        CreateRouteRecommendationResponse response =
                service.recommend(request(RouteFixture.USER_NEAR_AMAZON, EXAMPLE_FIVE));

        assertThat(response.visits()).extracting(Visit::attractionId)
                .containsExactly(AMAZON, MONIMO, SPACE_FIGHTER, BUMPER_CAR, THUNDER_FALLS);
        assertThat(response.totalMoveMinutes()).isEqualTo(25);
        assertThat(response.totalDistanceM()).isEqualTo(1285);
    }

    @Test
    @DisplayName("3. 선택 목록이 바뀌면(썬더폴스 → 허리케인) 허리케인→범퍼카→우주전투기→모니모→아마존, 27분")
    void recommend_selectionChanged() {
        List<Long> selection = List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON, MONIMO, HURRICANE);
        givenData(selection, path -> true);

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, selection));

        assertThat(response.visits()).extracting(Visit::attractionId)
                .containsExactly(HURRICANE, BUMPER_CAR, SPACE_FIGHTER, MONIMO, AMAZON);
        assertThat(response.totalMoveMinutes()).isEqualTo(27);
        assertThat(response.totalDistanceM()).isEqualTo(1508);
    }

    @Test
    @DisplayName("3-1. 같은 5개를 다른 순서로 보내도 결과는 같다")
    void recommend_sameSelectionDifferentInputOrder_sameResult() {
        List<Long> shuffled = List.of(AMAZON, THUNDER_FALLS, MONIMO, BUMPER_CAR, SPACE_FIGHTER);
        givenData(shuffled, path -> true);

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, shuffled));

        assertThat(response.visits()).extracting(Visit::attractionId)
                .containsExactly(BUMPER_CAR, THUNDER_FALLS, SPACE_FIGHTER, MONIMO, AMAZON);
    }

    // ===== 경로 데이터 처리 =====

    @Test
    @DisplayName("8. 비활성 구간(범퍼카→썬더폴스)은 계산에서 빼고 INACTIVE 로 알려준다")
    void recommend_inactivePath() {
        givenData(EXAMPLE_FIVE, path -> !(path.getFromAttractionId() == BUMPER_CAR
                && path.getToAttractionId() == THUNDER_FALLS));

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE));

        assertThat(response.unavailablePaths())
                .containsExactly(new UnavailablePath(BUMPER_CAR, THUNDER_FALLS, UnavailableReason.INACTIVE));
        assertThat(response.validOrderCount()).isEqualTo(96);
        assertThat(response.visits()).extracting(Visit::attractionId)
                .containsExactly(THUNDER_FALLS, BUMPER_CAR, SPACE_FIGHTER, MONIMO, AMAZON);
        assertThat(response.totalMoveMinutes()).isEqualTo(28);
        assertThat(response.totalDistanceM()).isEqualTo(1485);
    }

    @Test
    @DisplayName("9. 구간 데이터가 없으면(범퍼카→썬더폴스 행 없음) 값을 만들지 않고 MISSING 으로 알려준다")
    void recommend_missingPath() {
        givenAttractionsAndLocations(EXAMPLE_FIVE);
        List<AttractionPath> paths = new ArrayList<>(paths(EXAMPLE_FIVE, path -> true));
        paths.removeIf(path -> path.getFromAttractionId() == BUMPER_CAR && path.getToAttractionId() == THUNDER_FALLS);
        when(attractionPathRepository.findAllBetween(anyCollection())).thenReturn(paths);

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE));

        assertThat(response.unavailablePaths())
                .containsExactly(new UnavailablePath(BUMPER_CAR, THUNDER_FALLS, UnavailableReason.MISSING));
        assertThat(response.validOrderCount()).isEqualTo(96);
        assertThat(response.totalMoveMinutes()).isEqualTo(28);
    }

    @Test
    @DisplayName("is_estimated = true 인 구간을 쓰면 해당 구간과 응답 전체에 추정 표시가 된다")
    void recommend_estimatedPath() {
        givenAttractionsAndLocations(EXAMPLE_FIVE);
        List<AttractionPath> paths = new ArrayList<>();
        for (long[] row : RouteFixture.pathRowsBetween(EXAMPLE_FIVE)) {
            boolean estimated = row[0] == BUMPER_CAR && row[1] == THUNDER_FALLS;
            paths.add(AttractionPath.of(row[0], row[1], (int) row[2], (int) row[3], estimated, true));
        }
        when(attractionPathRepository.findAllBetween(anyCollection())).thenReturn(paths);

        CreateRouteRecommendationResponse response = service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE));

        assertThat(response.estimatedPathIncluded()).isTrue();
        assertThat(response.legs().get(1).estimated()).isTrue();
        assertThat(response.legs().get(2).estimated()).isFalse();
    }

    @Test
    @DisplayName("모든 구간이 비활성이면 ROUTE_NO_VALID_ORDER")
    void recommend_noValidOrder() {
        givenData(EXAMPLE_FIVE, path -> false);

        assertBusinessError(() -> service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE)),
                ErrorCode.ROUTE_NO_VALID_ORDER);
    }

    // ===== 입력·데이터 검증 =====

    @Test
    @DisplayName("4. 중복 ID 는 DB 조회 없이 DUPLICATE_ROUTE_ATTRACTION")
    void recommend_duplicateIds() {
        List<Long> duplicated = List.of(BUMPER_CAR, BUMPER_CAR, AMAZON, MONIMO, THUNDER_FALLS);

        assertBusinessError(() -> service.recommend(request(RouteFixture.USER_CENTER, duplicated)),
                ErrorCode.DUPLICATE_ROUTE_ATTRACTION);
        verifyNoInteractions(attractionRepository, attractionLocationRepository, attractionPathRepository);
    }

    @Test
    @DisplayName("위치 정확도가 100m 이상이면 DB 조회 없이 LOCATION_ACCURACY_TOO_LOW")
    void recommend_accuracyTooLow() {
        CreateRouteRecommendationRequest request = request(RouteFixture.USER_CENTER, EXAMPLE_FIVE, 100.0, null);

        assertBusinessError(() -> service.recommend(request), ErrorCode.LOCATION_ACCURACY_TOO_LOW);
        verifyNoInteractions(attractionRepository, attractionLocationRepository, attractionPathRepository);
    }

    @Test
    @DisplayName("위치 정확도 99.9m 는 허용한다 (기준: 100m 미만)")
    void recommend_accuracyJustBelowLimit_ok() {
        givenData(EXAMPLE_FIVE, path -> true);

        CreateRouteRecommendationResponse response =
                service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE, 99.9, null));

        assertThat(response.location().accuracy()).isEqualTo(99.9);
    }

    @Test
    @DisplayName("6. 존재하지 않는 ID 는 ATTRACTION_NOT_FOUND, 메시지에 없는 ID 를 담는다")
    void recommend_attractionNotFound() {
        when(attractionRepository.findAllById(anyIterable()))
                .thenReturn(attractions(List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON, MONIMO)));

        assertThatThrownBy(() -> service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("[5]")
                .extracting("errorCode").isEqualTo(ErrorCode.ATTRACTION_NOT_FOUND);
        verify(attractionPathRepository, never()).findAllBetween(anyCollection());
    }

    @Test
    @DisplayName("삭제된 어트랙션도 ATTRACTION_NOT_FOUND")
    void recommend_deletedAttraction() {
        List<Attraction> attractions = attractions(EXAMPLE_FIVE);
        ReflectionTestUtils.setField(attractions.get(0), "deletedAt", NOW);
        when(attractionRepository.findAllById(anyIterable())).thenReturn(attractions);

        assertBusinessError(() -> service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE)),
                ErrorCode.ATTRACTION_NOT_FOUND);
    }

    @Test
    @DisplayName("좌표가 없는 어트랙션이 있으면 ATTRACTION_LOCATION_NOT_FOUND")
    void recommend_locationMissing() {
        when(attractionRepository.findAllById(anyIterable())).thenReturn(attractions(EXAMPLE_FIVE));
        when(attractionLocationRepository.findAllByAttractionIdIn(anyCollection()))
                .thenReturn(locations(List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON, MONIMO)));

        assertThatThrownBy(() -> service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("[5]")
                .extracting("errorCode").isEqualTo(ErrorCode.ATTRACTION_LOCATION_NOT_FOUND);
        verify(attractionPathRepository, never()).findAllBetween(anyCollection());
    }

    @Test
    @DisplayName("7. 공원에서 2km 넘게 떨어진 위치(서울시청)는 LOCATION_OUT_OF_SERVICE_AREA")
    void recommend_outOfServiceArea() {
        givenAttractionsAndLocations(EXAMPLE_FIVE);

        assertBusinessError(() -> service.recommend(request(new GeoPoint(37.5665, 126.9780), EXAMPLE_FIVE)),
                ErrorCode.LOCATION_OUT_OF_SERVICE_AREA);
        verify(attractionPathRepository, never()).findAllBetween(anyCollection());
    }

    // ===== 위치 정보 응답 =====

    @Test
    @DisplayName("측정 시각이 5분보다 오래되면 STALE_POSITION 경고, 측정 시각은 KST 로 돌려준다")
    void recommend_stalePosition_warns() {
        givenData(EXAMPLE_FIVE, path -> true);
        long tenMinutesAgo = NOW.minus(Duration.ofMinutes(10)).toEpochMilli();

        CreateRouteRecommendationResponse response =
                service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE, 30.0, tenMinutesAgo));

        assertThat(response.location().warnings()).containsExactly(LocationWarning.STALE_POSITION);
        assertThat(response.location().measuredAt()).isEqualTo(LocalDateTime.of(2025, 11, 6, 11, 50));
    }

    @Test
    @DisplayName("측정 시각이 최근이거나 없으면 경고가 없다")
    void recommend_freshOrMissingTimestamp_noWarning() {
        givenData(EXAMPLE_FIVE, path -> true);
        long oneMinuteAgo = NOW.minus(Duration.ofMinutes(1)).toEpochMilli();

        assertThat(service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE, 30.0, oneMinuteAgo))
                .location().warnings()).isEmpty();

        CreateRouteRecommendationResponse withoutTimestamp =
                service.recommend(request(RouteFixture.USER_CENTER, EXAMPLE_FIVE, 30.0, null));
        assertThat(withoutTimestamp.location().warnings()).isEmpty();
        assertThat(withoutTimestamp.location().measuredAt()).isNull();
    }

    @Test
    @DisplayName("계산에 쓰지 않는 Geolocation 값(고도·방향·속도)도 응답에 그대로 돌려준다")
    void recommend_echoesGeolocationFields() {
        givenData(EXAMPLE_FIVE, path -> true);
        CreateRouteRecommendationRequest request = new CreateRouteRecommendationRequest(
                37.2930, 127.2010, 30.0, 45.2, 10.0, 180.0, 1.5, null, EXAMPLE_FIVE);

        CreateRouteRecommendationResponse response = service.recommend(request);

        assertThat(response.location().altitude()).isEqualTo(45.2);
        assertThat(response.location().altitudeAccuracy()).isEqualTo(10.0);
        assertThat(response.location().heading()).isEqualTo(180.0);
        assertThat(response.location().speed()).isEqualTo(1.5);
    }

    // ===== 테스트 도우미 =====

    private CreateRouteRecommendationRequest request(GeoPoint user, List<Long> attractionIds) {
        return request(user, attractionIds, 30.0, null);
    }

    private CreateRouteRecommendationRequest request(GeoPoint user, List<Long> attractionIds,
                                                     Double accuracy, Long timestamp) {
        return new CreateRouteRecommendationRequest(
                user.latitude(), user.longitude(), accuracy, null, null, null, null, timestamp, attractionIds);
    }

    private void givenData(List<Long> attractionIds, Predicate<AttractionPath> active) {
        givenAttractionsAndLocations(attractionIds);
        when(attractionPathRepository.findAllBetween(anyCollection())).thenReturn(paths(attractionIds, active));
    }

    private void givenAttractionsAndLocations(List<Long> attractionIds) {
        when(attractionRepository.findAllById(anyIterable())).thenReturn(attractions(attractionIds));
        when(attractionLocationRepository.findAllByAttractionIdIn(anyCollection())).thenReturn(locations(attractionIds));
    }

    private List<Attraction> attractions(List<Long> ids) {
        return ids.stream().map(id -> {
            Attraction attraction = Attraction.create(RouteFixture.NAMES.get(id), "설명", "구역", 5, 10);
            ReflectionTestUtils.setField(attraction, "id", id);
            return attraction;
        }).toList();
    }

    private List<AttractionLocation> locations(List<Long> ids) {
        return ids.stream().map(id -> {
            GeoPoint point = RouteFixture.LOCATIONS.get(id);
            return AttractionLocation.of(id, point.latitude(), point.longitude());
        }).toList();
    }

    // active 조건을 만족하는 구간만 is_active = true 로 만든다
    private List<AttractionPath> paths(List<Long> ids, Predicate<AttractionPath> active) {
        Set<Long> selected = Set.copyOf(ids);
        List<AttractionPath> paths = new ArrayList<>();
        for (long[] row : RouteFixture.PATHS) {
            if (!selected.contains(row[0]) || !selected.contains(row[1])) {
                continue;
            }
            AttractionPath probe = AttractionPath.of(row[0], row[1], (int) row[2], (int) row[3], false, true);
            paths.add(AttractionPath.of(row[0], row[1], (int) row[2], (int) row[3], false, active.test(probe)));
        }
        return paths;
    }

    private void assertBusinessError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}
