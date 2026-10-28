package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus;
import com.example.attractionreservation.domain.attraction.repository.AttractionRepository;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.CandidateSelector;
import com.example.attractionreservation.domain.routeRecommendation.algorithm.RouteSearcher;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteRecommendationRequest;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.example.attractionreservation.domain.routeRecommendation.dto.SearchRouteRecommendationSummaryResponse;
import com.example.attractionreservation.domain.routeRecommendation.repository.StopCount;
import com.example.attractionreservation.global.response.PageableDto;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyIterable;

@ExtendWith(MockitoExtension.class)
class RouteRecommendationServiceTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-20T05:00:00Z"), KstTime.ZONE);
    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 10, 20);
    private static final Long USER_ID = 7L;
    private static final Long TICKET_ID = 5001L;

    @Mock
    private RouteRecommendationRepository routeRecommendationRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private AttractionRepository attractionRepository;

    @Mock
    private AttractionSlotRepository attractionSlotRepository;
    private RouteRecommendationService routeRecommendationService;
    @Mock
    private ReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {

        routeRecommendationService = new RouteRecommendationService(
                routeRecommendationRepository,
                ticketRepository,
                attractionRepository,
                attractionSlotRepository,
                reservationRepository,
                new CandidateSelector(),
                new RouteSearcher(),
                FIXED_CLOCK
        );
    }

    //  공통 헬퍼

    private static LocalDateTime at(String hhmm) {
        return LocalDateTime.parse("2026-10-20T" + hhmm + ":00");
    }

    private RouteRecommendation recommendationOwnedBy(Long userId) {
        RouteRecommendation recommendation = RouteRecommendation.create(
                userId,
                TICKET_ID,
                at("13:00"),
                at("18:00"),
                List.of(
                        RouteRecommendationStop.of(1, 102L, 9201L, "사파리", 10, at("13:10"), at("13:30"), at("13:50")),
                        RouteRecommendationStop.of(2, 101L, 9001L, "T익스프레스", 15, at("14:05"), at("14:30"), at("14:40"))),
                List.of(RouteRecommendationExclusion.of(104L, "어트랙션 D", ExclusionReason.SLOT_FULL)));
        ReflectionTestUtils.setField(recommendation, "id", 1L);
        return recommendation;
    }

    // 생성 헬퍼

    private Ticket ticket(Long ownerId, TicketStatus status, TicketType type) {
        Ticket ticket = mock(Ticket.class, RETURNS_DEEP_STUBS);
        lenient().when(ticket.getId()).thenReturn(TICKET_ID);
        lenient().when(ticket.getOrderItem().getOrder().getUser().getId()).thenReturn(ownerId);
        lenient().doReturn(status).when(ticket).getStatus();
        lenient().when(ticket.getType()).thenReturn(type);
        lenient().when(ticket.getValidDate()).thenReturn(VISIT_DATE);
        return ticket;
    }

    private void givenUsableTicket() {
        Ticket ticket = ticket(USER_ID, TicketStatus.IN_USE, TicketType.ALL_DAY);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
    }

    private static Attraction attraction(Long id, String name, int durationMinutes, int moveMinutes) {
        Attraction attraction = Attraction.create(name, "설명", "A구역", durationMinutes, moveMinutes);
        ReflectionTestUtils.setField(attraction, "id", id);
        return attraction;
    }

    private static AttractionSlot slot(Long id, Attraction attraction, String startHhmm) {
        AttractionSlot slot = AttractionSlot.create(attraction, at(startHhmm), 30);
        ReflectionTestUtils.setField(slot, "id", id);
        return slot;
    }

    private void givenSlots(Long attractionId, AttractionSlot... slots) {
        when(attractionSlotRepository.findSlotsByAttractionIdAndDate(eq(attractionId), any(), any(), eq(false)))
                .thenReturn(List.of(slots));
    }

    private static CreateRouteRecommendationRequest request(String startHhmm, String endHhmm, Long... attractionIds) {
        return new CreateRouteRecommendationRequest(TICKET_ID, at(startHhmm), at(endHhmm), List.of(attractionIds));
    }

    private void givenTwoReachableAttractions() {
        Attraction a = attraction(101L, "T익스프레스", 10, 10);
        Attraction b = attraction(102L, "사파리", 20, 10);
        // 요청 순서와 상관없이 DB 는 순서를 보장하지 않는다 → 서비스가 요청 순서로 다시 맞추는지까지 확인
        when(attractionRepository.findAllById(anyIterable())).thenReturn(List.of(a, b));
        givenSlots(101L, slot(9001L, a, "15:00"));
        givenSlots(102L, slot(9201L, b, "15:30"));
    }

    private void givenSaveReturnsArgument() {
        when(routeRecommendationRepository.save(any(RouteRecommendation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private void givenReservationAt(String slotStartHhmm) {
        Attraction attraction = attraction(201L, "회전목마", 10, 5);
        Reservation reservation = new Reservation(mock(User.class), mock(Ticket.class),
                slot(9901L, attraction, slotStartHhmm));
        when(reservationRepository.findAllWithSlotByTicketIdAndStatus(TICKET_ID, ReservationStatus.RESERVED))
                .thenReturn(List.of(reservation));
    }

    // 생성: 정상

    @Test
    @DisplayName("모든 어트랙션을 방문할 수 있으면 최적 순서로 저장하고 FULL 로 응답한다")
    void createRouteRecommendation_AllReachable_SavesFull() {
        // given
        givenUsableTicket();
        givenTwoReachableAttractions();
        givenSaveReturnsArgument();

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService
                .createRouteRecommendation(USER_ID, request("14:00", "18:00", 102L, 101L));

        // then
        assertThat(response.resultType()).isEqualTo(RouteResultType.FULL);
        assertThat(response.totalSelectedAttractions()).isEqualTo(2);
        assertThat(response.totalRecommendedAttractions()).isEqualTo(2);
        assertThat(response.visits())
                .extracting(SearchRouteRecommendationResponse.Visit::attractionId)
                .containsExactly(101L, 102L);
        assertThat(response.totalDurationMinutes()).isEqualTo(110);
        assertThat(response.estimatedEndAt()).isEqualTo(at("15:50"));
        assertThat(response.excludedAttractions()).isEmpty();
        verify(routeRecommendationRepository).save(any(RouteRecommendation.class));
    }

    @Test
    @DisplayName("운영하지 않는 어트랙션은 제외 사유와 함께 응답하고 나머지로 PARTIAL 경로를 저장한다")
    void createRouteRecommendation_OneNotOperating_SavesPartial() {
        // given
        givenUsableTicket();
        Attraction a = attraction(101L, "T익스프레스", 10, 10);
        Attraction b = attraction(102L, "사파리", 20, 10);
        b.changeOperationStatus(OperationStatus.MAINTENANCE);
        when(attractionRepository.findAllById(List.of(101L, 102L))).thenReturn(List.of(a, b));
        givenSlots(101L, slot(9001L, a, "15:00"));
        givenSaveReturnsArgument();

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService
                .createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L, 102L));

        // then
        assertThat(response.resultType()).isEqualTo(RouteResultType.PARTIAL);
        assertThat(response.visits())
                .extracting(SearchRouteRecommendationResponse.Visit::attractionId)
                .containsExactly(101L);
        assertThat(response.excludedAttractions())
                .extracting(SearchRouteRecommendationResponse.ExcludedAttraction::reasonCode)
                .containsExactly("NOT_OPERATING");
    }

    @Test
    @DisplayName("방문 가능한 어트랙션이 없으면 저장하지 않고 NONE 과 제외 사유를 응답한다")
    void createRouteRecommendation_NothingReachable_ReturnsNoneWithoutSaving() {
        // given: 14:20 슬롯의 예약 마감은 13:50 → 14:00 기준 이미 지남
        givenUsableTicket();
        Attraction a = attraction(101L, "T익스프레스", 10, 10);
        when(attractionRepository.findAllById(List.of(101L))).thenReturn(List.of(a));
        givenSlots(101L, slot(9001L, a, "14:20"));

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService
                .createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L));

        // then
        assertThat(response.isNone()).isTrue();
        assertThat(response.recommendationId()).isNull();
        assertThat(response.totalSelectedAttractions()).isEqualTo(1);
        assertThat(response.visits()).isEmpty();
        assertThat(response.excludedAttractions())
                .extracting(SearchRouteRecommendationResponse.ExcludedAttraction::reasonCode)
                .containsExactly("RESERVATION_CLOSED");
        verify(routeRecommendationRepository, never()).save(any());
    }

    @Test
    @DisplayName("시작 시각이 이미 지났으면 현재 시각부터 추천한다")
    void createRouteRecommendation_PastStartAt_CorrectedToNow() {
        // given
        givenUsableTicket();
        givenTwoReachableAttractions();
        givenSaveReturnsArgument();

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService
                .createRouteRecommendation(USER_ID, request("12:00", "18:00", 101L, 102L));

        // then
        assertThat(response.startAt()).isEqualTo(at("14:00"));
        assertThat(response.totalDurationMinutes()).isEqualTo(110);
    }

    @Test
    @DisplayName("취소 마감이 지났고 끝나지 않은 예약이 있으면 ROUTE_RECOMMENDATION_BLOCKED 예외가 발생한다")
    void createRouteRecommendation_BlockedByReservation_ThrowsException() {
        // given: 14:20 탑승 → 취소 마감 13:50 지남, 종료 14:30 전 (현재 14:00)
        givenUsableTicket();
        givenReservationAt("14:20");

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L)),
                ErrorCode.ROUTE_RECOMMENDATION_BLOCKED);
        verifyNoInteractions(attractionRepository);
    }

    @ParameterizedTest(name = "예약 탑승 {0}")
    @CsvSource({
            "15:00",   // 취소 마감 14:30 전 → 아직 취소 가능
            "13:00"    // 13:10 에 이미 끝남
    })
    @DisplayName("취소 가능하거나 이미 끝난 예약은 추천 생성을 막지 않는다")
    void createRouteRecommendation_NotBlockingReservation_Creates(String slotStartHhmm) {
        // given
        givenUsableTicket();
        givenReservationAt(slotStartHhmm);
        givenTwoReachableAttractions();
        givenSaveReturnsArgument();

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService
                .createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L, 102L));

        // then
        assertThat(response.resultType()).isEqualTo(RouteResultType.FULL);
    }

    //생성: 검증 실패

    @Test
    @DisplayName("같은 어트랙션을 중복 선택하면 DB 를 조회하지 않고 INVALID_ATTRACTION_SELECTION 예외가 발생한다")
    void createRouteRecommendation_DuplicateAttraction_ThrowsException() {
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L, 101L)),
                ErrorCode.INVALID_ATTRACTION_SELECTION);
        verifyNoInteractions(ticketRepository, attractionRepository, routeRecommendationRepository);
    }

    @Test
    @DisplayName("없는 입장권이면 TICKET_NOT_FOUND 예외가 발생한다")
    void createRouteRecommendation_TicketNotFound_ThrowsException() {
        // given
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L)),
                ErrorCode.TICKET_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원의 입장권이면 TICKET_NOT_OWNED 예외가 발생한다")
    void createRouteRecommendation_OtherUsersTicket_ThrowsException() {
        // given
        Ticket otherUsersTicket = ticket(8L, TicketStatus.IN_USE, TicketType.ALL_DAY);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(otherUsersTicket));

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L)),
                ErrorCode.TICKET_NOT_OWNED);
    }

    @Test
    @DisplayName("사용 중이 아닌 입장권이면 TICKET_NOT_AVAILABLE 예외가 발생한다")
    void createRouteRecommendation_TicketNotInUse_ThrowsException() {
        // given
        Ticket unusedTicket = ticket(USER_ID, TicketStatus.UNUSED, TicketType.ALL_DAY);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(unusedTicket));

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L)),
                ErrorCode.TICKET_NOT_AVAILABLE);
    }

    @ParameterizedTest(name = "{0} ~ {1}")
    @CsvSource({
            "15:00, 15:00",   // 시작 = 종료
            "16:00, 15:00",   // 시작 > 종료
            "15:00, 22:30",   // 폐장 이후 종료
            "13:00, 13:30"    // 시작이 14:00 으로 보정되면 종료보다 늦다
    })
    @DisplayName("이용 시간 범위가 잘못되면 알고리즘을 실행하지 않고 INVALID_TIME_RANGE 예외가 발생한다")
    void createRouteRecommendation_InvalidTimeRange_ThrowsException(String startHhmm, String endHhmm) {
        // given
        givenUsableTicket();

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request(startHhmm, endHhmm, 101L)),
                ErrorCode.INVALID_TIME_RANGE);
        verifyNoInteractions(attractionRepository, attractionSlotRepository);
    }

    @Test
    @DisplayName("오후권으로 15:00 이전부터 추천을 요청하면 INVALID_TIME_RANGE 예외가 발생한다")
    void createRouteRecommendation_AfternoonTicketBeforeUsableTime_ThrowsException() {
        // given
        Ticket afternoonTicket = ticket(USER_ID, TicketStatus.IN_USE, TicketType.AFTERNOON);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(afternoonTicket));

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L)),
                ErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    @DisplayName("없거나 삭제된 어트랙션이 포함되면 ATTRACTION_NOT_FOUND 예외가 발생한다")
    void createRouteRecommendation_AttractionNotFound_ThrowsException() {
        // given: 102 는 조회되지 않음
        givenUsableTicket();
        when(attractionRepository.findAllById(List.of(101L, 102L)))
                .thenReturn(List.of(attraction(101L, "T익스프레스", 10, 10)));

        // when & then
        assertErrorCode(
                () -> routeRecommendationService.createRouteRecommendation(USER_ID, request("14:00", "18:00", 101L, 102L)),
                ErrorCode.ATTRACTION_NOT_FOUND);
        verify(routeRecommendationRepository, never()).save(any());
    }

    // 상세 조회

    @Test
    @DisplayName("본인 추천을 조회하면 방문 순서·시간·제외 사유를 응답으로 변환한다")
    void findRouteRecommendationById_Owner_ReturnsResponse() {
        // given
        when(routeRecommendationRepository.findDetailById(1L)).thenReturn(Optional.of(recommendationOwnedBy(7L)));

        // when
        SearchRouteRecommendationResponse response = routeRecommendationService.findRouteRecommendationById(7L, 1L);

        // then
        assertThat(response.recommendationId()).isEqualTo(1L);
        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
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
        assertErrorCode(() -> routeRecommendationService.findRouteRecommendationById(7L, 999L),
                ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원의 추천을 조회하면 NOT_ROUTE_OWNER 예외가 발생한다")
    void findRouteRecommendationById_OtherUser_ThrowsException() {
        // given
        when(routeRecommendationRepository.findDetailById(1L)).thenReturn(Optional.of(recommendationOwnedBy(7L)));

        // when & then
        assertErrorCode(() -> routeRecommendationService.findRouteRecommendationById(8L, 1L),
                ErrorCode.NOT_ROUTE_OWNER);
    }

    //삭제

    @Test
    @DisplayName("본인 추천을 삭제하면 서버 시각으로 삭제 시각이 기록된다")
    void deleteRouteRecommendation_Owner_SoftDeletes() {
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
    void deleteRouteRecommendation_NotFound_ThrowsException() {
        // given
        when(routeRecommendationRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        // when & then
        assertErrorCode(() -> routeRecommendationService.deleteRouteRecommendation(7L, 999L),
                ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 회원의 추천을 삭제하면 NOT_ROUTE_OWNER 예외가 발생하고 삭제되지 않는다")
    void deleteRouteRecommendation_OtherUser_ThrowsExceptionAndKeeps() {
        // given
        RouteRecommendation recommendation = recommendationOwnedBy(7L);
        when(routeRecommendationRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(recommendation));

        // when & then
        assertErrorCode(() -> routeRecommendationService.deleteRouteRecommendation(8L, 1L),
                ErrorCode.NOT_ROUTE_OWNER);
        assertThat(recommendation.isDeleted()).isFalse();
    }

    // ---------- 목록 조회 ----------

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
