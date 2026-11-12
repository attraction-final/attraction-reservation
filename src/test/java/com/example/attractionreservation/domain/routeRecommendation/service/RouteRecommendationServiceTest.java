package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.order.entity.Order;
import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.routeRecommendation.RouteFixture;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelector;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.GeoPoint;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.HaversineStartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearcher;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLeg;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.StartLegEstimator;
import com.example.attractionreservation.domain.routeRecommendation.config.RouteRecommendationProperties;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationResponse.LocationWarning;
import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.ExcludedAttraction;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse.Visit;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionLocationRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.AttractionPathRepository;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.AMAZON;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.BUMPER_CAR;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.EXAMPLE_FIVE;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.SPACE_FIGHTER;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.THUNDER_FALLS;
import static com.example.attractionreservation.domain.routeRecommendation.RouteFixture.USER_CENTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 저장소·입장권 서비스만 Mock 으로 두고, 후보 선정(CandidateSelector)·경로 탐색(RouteSearcher)·
 * 첫 구간 추정(HaversineStartLegEstimator)은 실제 객체를 써서 "검증 → 계산 → 저장" 흐름 전체를 확인한다.
 * 슬롯은 어트랙션마다 10:30 ~ 21:30 사이 30분 간격(탑승 5분)으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class RouteRecommendationServiceTest {

    private static final LocalDate VISIT_DATE = LocalDate.of(2025, 11, 6);
    private static final LocalDateTime NOW = VISIT_DATE.atTime(12, 0);
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TICKET_ID = 10L;

    @Mock
    private RouteRecommendationRepository routeRecommendationRepository;
    @Mock
    private TicketService ticketService;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private AttractionRepository attractionRepository;
    @Mock
    private AttractionSlotRepository attractionSlotRepository;
    @Mock
    private AttractionLocationRepository attractionLocationRepository;
    @Mock
    private AttractionPathRepository attractionPathRepository;

    @Mock
    private Ticket ticket;
    @Mock
    private OrderItem orderItem;
    @Mock
    private Order order;
    @Mock
    private User user;

    private RouteRecommendationProperties properties;
    private RouteRecommendationService service;

    // 테스트마다 만든 어트랙션·슬롯 (슬롯 정원이 바뀌지 않았는지 확인할 때 쓴다)
    private final Map<Long, Attraction> attractionsById = new HashMap<>();
    private final Map<Long, List<AttractionSlot>> slotsByAttractionId = new HashMap<>();

    @BeforeEach
    void setUp() {
        properties = new RouteRecommendationProperties();
        service = serviceAt(NOW, new HaversineStartLegEstimator(properties));

        // 정상 입장권: 본인 소유, 입장 처리됨(IN_USE), 방문일 = 오늘
        lenient().when(ticketService.findByTicketId(TICKET_ID)).thenReturn(ticket);
        lenient().when(ticket.getId()).thenReturn(TICKET_ID);
        lenient().when(ticket.getOrderItem()).thenReturn(orderItem);
        lenient().when(orderItem.getOrder()).thenReturn(order);
        lenient().when(order.getUser()).thenReturn(user);
        lenient().when(user.getId()).thenReturn(USER_ID);
        lenient().when(ticket.getStatus()).thenReturn(TicketStatus.IN_USE);
        lenient().when(ticket.getValidDate()).thenReturn(VISIT_DATE);
    }

    // ===== 추천 가능한 어트랙션 목록 =====

    @Test
    @DisplayName("추천 가능한 어트랙션 목록은 좌표 저장소의 조회 결과를 그대로 돌려준다")
    void findRecommendableAttractions_RepositoryResult_ReturnedAsIs() {
        // given
        List<RecommendableAttractionResponse> expected =
                List.of(new RecommendableAttractionResponse(1L, "범퍼카", 37.2923658, 127.1995663));
        when(attractionLocationRepository.findRecommendableAttractions()).thenReturn(expected);

        // when
        List<RecommendableAttractionResponse> result = service.findRecommendableAttractions();

        // then
        assertThat(result).isEqualTo(expected);
        verifyNoInteractions(attractionRepository, attractionPathRepository);
    }

    // ===== 입력·입장권·기존 예약 검증 =====

    @Test
    @DisplayName("이용 예정 예약이 있으면 ROUTE_RECOMMENDATION_BLOCKED, 어트랙션·좌표·슬롯은 조회하지 않는다")
    void createRouteRecommendation_UpcomingReservation_Blocked() {
        // given
        when(reservationRepository.existsByUser_IdAndStatusAndAttractionSlot_EndsAtAfter(
                USER_ID, ReservationStatus.RESERVED, NOW)).thenReturn(true);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.ROUTE_RECOMMENDATION_BLOCKED);
        verifyNoInteractions(attractionRepository, attractionSlotRepository, attractionLocationRepository,
                attractionPathRepository, routeRecommendationRepository);
    }

    @Test
    @DisplayName("다른 회원의 입장권이면 TICKET_ACCESS_DENIED")
    void createRouteRecommendation_TicketNotOwned_Denied() {
        // given
        when(user.getId()).thenReturn(OTHER_USER_ID);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.TICKET_ACCESS_DENIED);
        verifyNoInteractions(reservationRepository, attractionRepository);
    }

    @Test
    @DisplayName("입장 처리 전(UNUSED) 입장권이면 TICKET_RESERVATION_NOT_ALLOWED_STATUS")
    void createRouteRecommendation_TicketNotEntered_Rejected() {
        // given
        when(ticket.getStatus()).thenReturn(TicketStatus.UNUSED);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS);
        verifyNoInteractions(reservationRepository, attractionRepository);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TicketStatus.class, names = {"USED", "EXPIRED", "REFUNDED"})
    @DisplayName("사용 완료·만료·환불된 입장권이면 TICKET_RESERVATION_NOT_ALLOWED_STATUS")
    void createRouteRecommendation_TicketUsedOrExpired_Rejected(TicketStatus status) {
        // given
        when(ticket.getStatus()).thenReturn(status);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS);
        verifyNoInteractions(reservationRepository, attractionRepository);
    }

    @Test
    @DisplayName("방문일이 오늘이 아니면(내일) TICKET_ENTRY_DATE_MISMATCH")
    void createRouteRecommendation_TicketNotToday_Rejected() {
        // given
        when(ticket.getValidDate()).thenReturn(VISIT_DATE.plusDays(1));

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.TICKET_ENTRY_DATE_MISMATCH);
        verifyNoInteractions(reservationRepository, attractionRepository);
    }

    @Test
    @DisplayName("위치 정확도가 100m 이상이면 LOCATION_ACCURACY_TOO_LOW, 입장권도 조회하지 않는다")
    void createRouteRecommendation_LowAccuracy_Rejected() {
        // given
        CreateRouteRecommendationRequest request = request(EXAMPLE_FIVE, USER_CENTER, 100.0, null);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request);

        // then
        assertBusinessError(call, ErrorCode.LOCATION_ACCURACY_TOO_LOW);
        verifyNoInteractions(ticketService, reservationRepository, attractionRepository);
    }

    @Test
    @DisplayName("요청한 어트랙션 ID 중 존재하지 않는 것이 있으면 ATTRACTION_NOT_FOUND, 좌표·구간은 조회하지 않는다")
    void createRouteRecommendation_AttractionNotExists_NotFound() {
        // given
        List<Long> existing = EXAMPLE_FIVE.subList(0, 4);
        givenAttractions(existing);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.ATTRACTION_NOT_FOUND);
        verifyNoInteractions(attractionLocationRepository, attractionPathRepository);
    }

    @Test
    @DisplayName("삭제된 어트랙션을 요청하면 ATTRACTION_NOT_FOUND")
    void createRouteRecommendation_DeletedAttraction_NotFound() {
        // given
        givenAttractions(EXAMPLE_FIVE);
        ReflectionTestUtils.setField(attractionsById.get(EXAMPLE_FIVE.get(0)), "deletedAt",
                NOW.atZone(KstTime.ZONE).toInstant());

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.ATTRACTION_NOT_FOUND);
        verifyNoInteractions(attractionLocationRepository, attractionPathRepository);
    }

    @Test
    @DisplayName("위치 정확도 99.9m 는 허용한다 (기준: 100m 미만)")
    void createRouteRecommendation_AccuracyJustBelowLimit_Allowed() {
        // given
        givenAttractions(EXAMPLE_FIVE);
        givenLocations(EXAMPLE_FIVE);
        givenSlots(EXAMPLE_FIVE);
        givenPaths(EXAMPLE_FIVE, path -> true);
        givenSaveReturnsArgument();

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(
                USER_ID, request(EXAMPLE_FIVE, USER_CENTER, 99.9, null));

        // then
        assertThat(response.location().accuracy()).isEqualTo(99.9);
    }

    @Test
    @DisplayName("같은 어트랙션을 두 번 고르면 DUPLICATE_ROUTE_ATTRACTION, 입장권도 조회하지 않는다")
    void createRouteRecommendation_DuplicateAttraction_Rejected() {
        // given
        CreateRouteRecommendationRequest request = request(List.of(BUMPER_CAR, BUMPER_CAR));

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request);

        // then
        assertBusinessError(call, ErrorCode.DUPLICATE_ROUTE_ATTRACTION);
        verifyNoInteractions(ticketService, reservationRepository, attractionRepository);
    }

    // ===== 시작·종료 시각 =====

    @Test
    @DisplayName("현재 10:03:20 이면 시작은 10:04 로 올리고, 종료는 방문일 22:00 이다")
    void createRouteRecommendation_NowWithSeconds_StartRoundedUp() {
        // given
        service = serviceAt(VISIT_DATE.atTime(10, 3, 20), new HaversineStartLegEstimator(properties));
        List<Long> ids = List.of(BUMPER_CAR, THUNDER_FALLS);
        givenAttractions(ids);
        givenLocations(ids);
        givenSlots(ids);
        givenPaths(ids, path -> true);
        givenSaveReturnsArgument();

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(USER_ID, request(ids));

        // then
        RouteRecommendation saved = captureSaved();
        assertThat(saved.getStartAt()).isEqualTo(VISIT_DATE.atTime(10, 4));
        assertThat(saved.getEndAt()).isEqualTo(VISIT_DATE.atTime(22, 0));
        assertThat(response.recommendation().startAt()).isEqualTo(VISIT_DATE.atTime(10, 4));
        assertThat(response.recommendation().endAt()).isEqualTo(VISIT_DATE.atTime(22, 0));
    }

    @Test
    @DisplayName("현재 22:00 이면 시작이 종료(22:00)보다 앞서지 않으므로 INVALID_TIME_RANGE")
    void createRouteRecommendation_AfterClose_InvalidTimeRange() {
        // given
        service = serviceAt(VISIT_DATE.atTime(22, 0), new HaversineStartLegEstimator(properties));

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.INVALID_TIME_RANGE);
        verifyNoInteractions(attractionRepository, attractionSlotRepository, routeRecommendationRepository);
    }

    // ===== 좌표 =====

    @Test
    @DisplayName("3개 중 1개만 좌표가 없으면 그 어트랙션만 LOCATION_NOT_FOUND 로 빼고 PARTIAL 로 저장한다")
    void createRouteRecommendation_MissingCoordinate_ExcludedPartial() {
        // given
        List<Long> ids = List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON);
        List<Long> located = List.of(BUMPER_CAR, AMAZON);
        givenAttractions(ids);
        givenLocations(located);
        givenSlots(ids);
        givenPaths(located, path -> true);
        givenSaveReturnsArgument();

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(USER_ID, request(ids));

        // then
        SearchRouteRecommendationResponse recommendation = response.recommendation();
        assertThat(recommendation.resultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(recommendation.totalSelectedAttractions()).isEqualTo(3);
        assertThat(recommendation.visits()).extracting(Visit::attractionId)
                .containsExactlyInAnyOrder(BUMPER_CAR, AMAZON);
        assertThat(recommendation.excludedAttractions())
                .extracting(ExcludedAttraction::attractionId, ExcludedAttraction::attractionName,
                        ExcludedAttraction::reasonCode)
                .containsExactly(tuple(SPACE_FIGHTER, "우주전투기", ExclusionReason.LOCATION_NOT_FOUND.name()));
        // 좌표 없는 어트랙션은 슬롯도 조회하지 않는다
        verify(attractionSlotRepository, never())
                .findSlotsByAttractionIdAndDate(eq(SPACE_FIGHTER), any(), any(), anyBoolean());
        verify(routeRecommendationRepository, times(1)).save(any(RouteRecommendation.class));
    }

    @Test
    @DisplayName("좌표가 전부 없으면 예외 없이 NONE 을 돌려주고 저장하지 않는다")
    void createRouteRecommendation_AllCoordinatesMissing_None() {
        // given
        List<Long> ids = List.of(BUMPER_CAR, SPACE_FIGHTER, AMAZON);
        givenAttractions(ids);
        givenLocations(List.of());

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(USER_ID, request(ids));

        // then
        SearchRouteRecommendationResponse recommendation = response.recommendation();
        assertThat(recommendation.isNone()).isTrue();
        assertThat(recommendation.recommendationId()).isNull();
        assertThat(recommendation.totalSelectedAttractions()).isEqualTo(3);
        assertThat(recommendation.visits()).isEmpty();
        assertThat(recommendation.excludedAttractions())
                .extracting(ExcludedAttraction::attractionId, ExcludedAttraction::reasonCode)
                .containsExactly(
                        tuple(BUMPER_CAR, ExclusionReason.LOCATION_NOT_FOUND.name()),
                        tuple(SPACE_FIGHTER, ExclusionReason.LOCATION_NOT_FOUND.name()),
                        tuple(AMAZON, ExclusionReason.LOCATION_NOT_FOUND.name()));
        assertThat(response.location()).isNotNull();
        verify(routeRecommendationRepository, never()).save(any());
        verifyNoInteractions(attractionSlotRepository, attractionPathRepository);
    }

    // ===== 결과 유형 =====

    @Test
    @DisplayName("현재 21:50 이면 이용 시간 안에 들어오는 슬롯이 없어 NONE 이고 저장하지 않는다")
    void createRouteRecommendation_NearClose_None() {
        // given
        service = serviceAt(VISIT_DATE.atTime(21, 50), new HaversineStartLegEstimator(properties));
        List<Long> ids = List.of(BUMPER_CAR, THUNDER_FALLS);
        givenAttractions(ids);
        givenLocations(ids);
        givenSlots(ids);

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(USER_ID, request(ids));

        // then
        SearchRouteRecommendationResponse recommendation = response.recommendation();
        assertThat(recommendation.resultType()).isEqualTo(RouteResultType.NONE);
        assertThat(recommendation.startAt()).isEqualTo(VISIT_DATE.atTime(21, 50));
        assertThat(recommendation.excludedAttractions())
                .extracting(ExcludedAttraction::attractionId, ExcludedAttraction::reasonCode)
                .containsExactly(
                        tuple(BUMPER_CAR, ExclusionReason.OUT_OF_TIME_RANGE.name()),
                        tuple(THUNDER_FALLS, ExclusionReason.OUT_OF_TIME_RANGE.name()));
        verify(routeRecommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("가장 가까운 선택 어트랙션까지 2,001m 이면 LOCATION_OUT_OF_SERVICE_AREA, 슬롯은 조회하지 않는다")
    void createRouteRecommendation_OutOfServiceArea_Rejected() {
        // given
        StartLegEstimator farAway = (userLocation, attractionLocation) -> new StartLeg(2001, 3362, 64);
        service = serviceAt(NOW, farAway);
        givenAttractions(EXAMPLE_FIVE);
        givenLocations(EXAMPLE_FIVE);

        // when
        ThrowingCallable call = () -> service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        assertBusinessError(call, ErrorCode.LOCATION_OUT_OF_SERVICE_AREA);
        verifyNoInteractions(attractionSlotRepository, attractionPathRepository, routeRecommendationRepository);
    }

    @Test
    @DisplayName("모두 방문할 수 있으면 FULL 로 1번 저장하고, 예약 생성·슬롯 정원 변경은 하지 않는다 (BR-RTE-30)")
    void createRouteRecommendation_Full_SavedWithoutReservation() {
        // given
        givenAttractions(EXAMPLE_FIVE);
        givenLocations(EXAMPLE_FIVE);
        givenSlots(EXAMPLE_FIVE);
        givenPaths(EXAMPLE_FIVE, path -> true);
        givenSaveReturnsArgument();

        // when
        CreateRouteRecommendationResponse response =
                service.createRouteRecommendation(USER_ID, request(EXAMPLE_FIVE));

        // then
        SearchRouteRecommendationResponse recommendation = response.recommendation();
        assertThat(recommendation.resultType()).isEqualTo(RouteResultType.FULL);
        assertThat(recommendation.ticketId()).isEqualTo(TICKET_ID);
        assertThat(recommendation.totalSelectedAttractions()).isEqualTo(5);
        assertThat(recommendation.totalRecommendedAttractions()).isEqualTo(5);
        assertThat(recommendation.excludedAttractions()).isEmpty();
        assertThat(recommendation.estimatedValues()).isTrue();
        assertThat(recommendation.visits()).extracting(Visit::attractionId)
                .containsExactlyInAnyOrderElementsOf(EXAMPLE_FIVE);
        assertThat(recommendation.visits().get(0).moveEstimated()).isTrue(); // 첫 구간은 항상 추정값

        RouteRecommendation saved = captureSaved();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(saved.getStops()).hasSize(5);
        assertThat(saved.getExclusions()).isEmpty();

        // 예약은 기존 예약 확인 1번만, 슬롯은 어트랙션별 조회만 한다 (생성·정원 변경 없음)
        verify(reservationRepository).existsByUser_IdAndStatusAndAttractionSlot_EndsAtAfter(
                USER_ID, ReservationStatus.RESERVED, NOW);
        verifyNoMoreInteractions(reservationRepository);
        verify(attractionSlotRepository, times(5))
                .findSlotsByAttractionIdAndDate(anyLong(), any(), any(), anyBoolean());
        verifyNoMoreInteractions(attractionSlotRepository);
        assertThat(slotsByAttractionId.values()).isNotEmpty().allSatisfy(slots ->
                assertThat(slots).isNotEmpty().allSatisfy(slot -> assertThat(slot.getReservedCount()).isZero()));
    }

    @Test
    @DisplayName("비활성 구간은 이동에 쓰지 않는다: 두 어트랙션 사이 구간이 모두 비활성이면 하나만 방문하고 나머지는 TIME_CONFLICT")
    void createRouteRecommendation_InactivePathsOnly_PartialWithTimeConflict() {
        // given
        List<Long> ids = List.of(BUMPER_CAR, THUNDER_FALLS);
        givenAttractions(ids);
        givenLocations(ids);
        givenSlots(ids);
        givenPaths(ids, path -> false);
        givenSaveReturnsArgument();

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(USER_ID, request(ids));

        // then
        SearchRouteRecommendationResponse recommendation = response.recommendation();
        assertThat(recommendation.resultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(recommendation.visits()).hasSize(1);
        assertThat(recommendation.excludedAttractions())
                .extracting(ExcludedAttraction::reasonCode)
                .containsExactly(ExclusionReason.TIME_CONFLICT.name());
        RouteRecommendation saved = captureSaved();
        assertThat(saved.getExclusions()).extracting(RouteRecommendationExclusion::getReasonCode)
                .containsExactly(ExclusionReason.TIME_CONFLICT);
    }

    // ===== 위치 응답 =====

    @Test
    @DisplayName("측정 시각이 6분 전이면 location.warnings 에 STALE_POSITION 이 붙는다")
    void createRouteRecommendation_StaleTimestamp_Warning() {
        // given
        List<Long> ids = List.of(BUMPER_CAR, THUNDER_FALLS);
        givenAttractions(ids);
        givenLocations(ids);
        givenSlots(ids);
        givenPaths(ids, path -> true);
        givenSaveReturnsArgument();
        long sixMinutesAgo = NOW.minus(Duration.ofMinutes(6)).atZone(KstTime.ZONE).toInstant().toEpochMilli();

        // when
        CreateRouteRecommendationResponse response = service.createRouteRecommendation(
                USER_ID, request(ids, USER_CENTER, 30.0, sixMinutesAgo));

        // then
        assertThat(response.location().warnings()).containsExactly(LocationWarning.STALE_POSITION);
        assertThat(response.location().measuredAt()).isEqualTo(VISIT_DATE.atTime(11, 54));
    }

    // ===== 테스트 도우미 =====

    private RouteRecommendationService serviceAt(LocalDateTime now, StartLegEstimator startLegEstimator) {
        Clock clock = Clock.fixed(now.atZone(KstTime.ZONE).toInstant(), KstTime.ZONE);
        return new RouteRecommendationService(
                routeRecommendationRepository,
                ticketService,
                reservationRepository,
                attractionRepository,
                attractionSlotRepository,
                attractionLocationRepository,
                attractionPathRepository,
                new CandidateSelector(),
                new RouteSearcher(),
                startLegEstimator,
                properties,
                clock);
    }

    private CreateRouteRecommendationRequest request(List<Long> attractionIds) {
        return request(attractionIds, USER_CENTER, 30.0, null);
    }

    private CreateRouteRecommendationRequest request(List<Long> attractionIds, GeoPoint user,
                                                     Double accuracy, Long timestamp) {
        return new CreateRouteRecommendationRequest(
                TICKET_ID, attractionIds, user.latitude(), user.longitude(), accuracy,
                null, null, null, null, timestamp);
    }

    private void givenAttractions(List<Long> ids) {
        List<Attraction> attractions = new ArrayList<>();
        for (Long id : ids) {
            Attraction attraction = Attraction.create(RouteFixture.NAMES.get(id), "설명", "구역", 5, 10);
            ReflectionTestUtils.setField(attraction, "id", id);
            attractionsById.put(id, attraction);
            attractions.add(attraction);
        }
        when(attractionRepository.findAllById(anyIterable())).thenReturn(attractions);
    }

    private void givenLocations(List<Long> ids) {
        List<AttractionLocation> locations = ids.stream().map(id -> {
            GeoPoint point = RouteFixture.LOCATIONS.get(id);
            return AttractionLocation.of(id, point.latitude(), point.longitude());
        }).toList();
        when(attractionLocationRepository.findAllByAttractionIdIn(anyCollection())).thenReturn(locations);
    }

    // 어트랙션마다 10:30 ~ 21:30 사이 30분 간격 슬롯 (탑승 5분, 정원 20, 예약 0)
    private void givenSlots(List<Long> ids) {
        for (Long id : ids) {
            List<AttractionSlot> slots = new ArrayList<>();
            LocalDateTime startsAt = VISIT_DATE.atTime(10, 30);
            long sequence = 0;
            while (!startsAt.isAfter(VISIT_DATE.atTime(21, 30))) {
                AttractionSlot slot = AttractionSlot.create(attractionsById.get(id), startsAt, 20);
                ReflectionTestUtils.setField(slot, "id", id * 1000 + sequence++);
                slots.add(slot);
                startsAt = startsAt.plusMinutes(30);
            }
            slotsByAttractionId.put(id, slots);
        }
        when(attractionSlotRepository.findSlotsByAttractionIdAndDate(anyLong(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> slotsByAttractionId.getOrDefault(invocation.getArgument(0), List.of()));
    }

    // active 조건을 만족하는 구간만 is_active = true 로 만든다
    private void givenPaths(List<Long> ids, Predicate<long[]> active) {
        Set<Long> selected = Set.copyOf(ids);
        List<AttractionPath> paths = new ArrayList<>();
        for (long[] row : RouteFixture.PATHS) {
            if (selected.contains(row[0]) && selected.contains(row[1])) {
                paths.add(AttractionPath.of(row[0], row[1], (int) row[2], (int) row[3], false, active.test(row)));
            }
        }
        when(attractionPathRepository.findAllBetween(anyCollection())).thenReturn(paths);
    }

    private void givenSaveReturnsArgument() {
        when(routeRecommendationRepository.save(any(RouteRecommendation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private RouteRecommendation captureSaved() {
        ArgumentCaptor<RouteRecommendation> captor = ArgumentCaptor.forClass(RouteRecommendation.class);
        verify(routeRecommendationRepository, times(1)).save(captor.capture());
        return captor.getValue();
    }

    private void assertBusinessError(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }
}