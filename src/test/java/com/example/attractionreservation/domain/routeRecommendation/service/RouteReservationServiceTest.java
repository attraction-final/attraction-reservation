package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteReservationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteReservationResponse.ReservedStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationExclusion;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.ExclusionReason;
import com.example.attractionreservation.domain.routeRecommendation.entity.enums.RouteResultType;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteReservationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long TICKET_ID = 77L;
    private static final Long RECOMMENDATION_ID = 500L;
    private static final LocalDate VISIT_DATE = LocalDate.of(2026, 11, 11);

    @Mock
    private RouteRecommendationRepository routeRecommendationRepository;

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private RouteReservationService routeReservationService;

    @Test
    @DisplayName("추천의 모든 방문을 예약할 수 있으면 어트랙션 ID 순으로 예약하고 방문 순서대로 결과를 반환한다")
    void createRouteReservations_AllAvailable_AllReserved() {
        // given
        // 방문 순서: 1번째 어트랙션 30, 2번째 어트랙션 10, 3번째 어트랙션 20
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any())).thenAnswer(invocation -> {
            CreateReservationRequest request = invocation.getArgument(1);
            return reservationResponse(request.slotId());
        });

        // when
        CreateRouteReservationResponse response =
                routeReservationService.createRouteReservations(USER_ID, RECOMMENDATION_ID);

        // then
        InOrder inOrder = inOrder(reservationService);
        inOrder.verify(reservationService).createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(10L)));
        inOrder.verify(reservationService).createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(20L)));
        inOrder.verify(reservationService).createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(30L)));
        inOrder.verifyNoMoreInteractions();

        assertThat(response.recommendationId()).isEqualTo(RECOMMENDATION_ID);
        assertThat(response.reservedCount()).isEqualTo(3);
        assertThat(response.reservations())
                .extracting(ReservedStop::sequence, ReservedStop::attractionId, ReservedStop::attractionName,
                        ReservedStop::slotId, ReservedStop::reservationId, ReservedStop::reservationNumber)
                .containsExactly(
                        tuple(1, 30L, "어트랙션30", slotIdOf(30L), reservationIdOf(slotIdOf(30L)), "R-" + slotIdOf(30L)),
                        tuple(2, 10L, "어트랙션10", slotIdOf(10L), reservationIdOf(slotIdOf(10L)), "R-" + slotIdOf(10L)),
                        tuple(3, 20L, "어트랙션20", slotIdOf(20L), reservationIdOf(slotIdOf(20L)), "R-" + slotIdOf(20L)));
        assertThat(response.reservations().get(0).startsAt()).isEqualTo(VISIT_DATE.atTime(13, 0));
        assertThat(response.reservations().get(0).endsAt()).isEqualTo(VISIT_DATE.atTime(13, 10));
    }

    @Test
    @DisplayName("부분 추천은 추천에 포함된 방문만 예약하고 제외된 어트랙션은 예약하지 않는다")
    void createRouteReservations_Partial_ReservesIncludedVisitsOnly() {
        // given
        Long excludedAttractionId = 99L;
        RouteRecommendation recommendation = recommendation(USER_ID,
                List.of(stop(1, 10L, VISIT_DATE.atTime(14, 0)), stop(2, 20L, VISIT_DATE.atTime(15, 0))),
                List.of(RouteRecommendationExclusion.of(excludedAttractionId, "어트랙션99", ExclusionReason.SLOT_FULL)));
        assertThat(recommendation.getResultType()).isEqualTo(RouteResultType.PARTIAL);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any())).thenAnswer(invocation -> {
            CreateReservationRequest request = invocation.getArgument(1);
            return reservationResponse(request.slotId());
        });

        // when
        CreateRouteReservationResponse response =
                routeReservationService.createRouteReservations(USER_ID, RECOMMENDATION_ID);

        // then
        verify(reservationService, times(2)).createReservation(eq(USER_ID), any());
        verify(reservationService, never())
                .createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(excludedAttractionId)));
        assertThat(response.reservedCount()).isEqualTo(2);
        assertThat(response.reservations()).extracting(ReservedStop::attractionId).containsExactly(10L, 20L);
    }

    @Test
    @DisplayName("추천 후 슬롯이 매진되면 일괄 예약 전체를 실패로 돌리고 남은 방문은 예약하지 않는다")
    void createRouteReservations_SoldOutAfterRecommendation_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        // 처리 순서: 어트랙션 10(2번째 방문) → 20(3번째 방문) → 30(1번째 방문). 두 번째 호출(어트랙션 20)이 실패한다.
        when(reservationService.createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(10L))))
                .thenReturn(reservationResponse(slotIdOf(10L)));
        when(reservationService.createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(20L))))
                .thenThrow(new BusinessException(ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED));

        // when
        // then
        assertRouteReservationFailed(
                "3번째 방문 '어트랙션20' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.SLOT_CAPACITY_ACQUISITION_FAILED.getMessage());
        verify(reservationService, never())
                .createReservation(USER_ID, new CreateReservationRequest(TICKET_ID, slotIdOf(30L)));
    }

    @Test
    @DisplayName("예약 마감 시각이 지나 첫 예약이 실패하면 일괄 예약 전체를 실패로 돌리고 이후 방문은 예약하지 않는다")
    void createRouteReservations_DeadlinePassed_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_DEADLINE_PASSED));

        // when
        // then
        assertRouteReservationFailed(
                "2번째 방문 '어트랙션10' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.RESERVATION_DEADLINE_PASSED.getMessage());
        verify(reservationService, times(1)).createReservation(eq(USER_ID), any());
    }

    @Test
    @DisplayName("추천 후 새로 생긴 예약과 시간이 겹치면 일괄 예약 전체를 실패로 돌린다")
    void createRouteReservations_ConflictWithNewReservation_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT));

        // when
        // then
        assertRouteReservationFailed(
                "2번째 방문 '어트랙션10' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.RESERVATION_TIME_CONFLICT.getMessage());
    }

    @Test
    @DisplayName("입장권이 더 이상 이용 중 상태가 아니면 일괄 예약 전체를 실패로 돌린다")
    void createRouteReservations_TicketNoLongerInUse_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS));

        // when
        // then
        assertRouteReservationFailed(
                "2번째 방문 '어트랙션10' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS.getMessage());
    }

    @Test
    @DisplayName("동시 요청으로 예약 유일 제약을 위반하면 중복 예약 사유로 일괄 예약 전체를 실패로 돌린다")
    void createRouteReservations_DuplicateKey_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any()))
                .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

        // when
        // then
        assertRouteReservationFailed(
                "2번째 방문 '어트랙션10' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.RESERVATION_DUPLICATED.getMessage());
    }

    @Test
    @DisplayName("잠금 대기 시간 초과로 예약이 실패하면 잠금 실패 사유로 일괄 예약 전체를 실패로 돌린다")
    void createRouteReservations_LockTimeout_FailsAll() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));
        when(reservationService.createReservation(eq(USER_ID), any()))
                .thenThrow(new PessimisticLockingFailureException("Lock wait timeout"));

        // when
        // then
        assertRouteReservationFailed(
                "2번째 방문 '어트랙션10' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: "
                        + ErrorCode.CONCURRENT_UPDATE_CONFLICT.getMessage());
        verify(reservationService, times(1)).createReservation(eq(USER_ID), any());
    }

    @Test
    @DisplayName("다른 회원의 추천 경로는 일괄 예약할 수 없다")
    void createRouteReservations_OtherUser_Forbidden() {
        // given
        RouteRecommendation recommendation = threeStopRecommendation(OTHER_USER_ID);
        when(routeRecommendationRepository.findDetailById(RECOMMENDATION_ID)).thenReturn(Optional.of(recommendation));

        // when
        // then
        assertThatThrownBy(() -> routeReservationService.createRouteReservations(USER_ID, RECOMMENDATION_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_ROUTE_OWNER));
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("존재하지 않는 추천 경로는 일괄 예약할 수 없다")
    void createRouteReservations_NotExists_NotFound() {
        // given
        when(routeRecommendationRepository.findDetailById(anyLong())).thenReturn(Optional.empty());

        // when
        // then
        assertThatThrownBy(() -> routeReservationService.createRouteReservations(USER_ID, RECOMMENDATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));
        verifyNoInteractions(reservationService);
    }

    // ===== 테스트 도우미 =====

    private void assertRouteReservationFailed(String expectedMessage) {
        assertThatThrownBy(() -> routeReservationService.createRouteReservations(USER_ID, RECOMMENDATION_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROUTE_RESERVATION_FAILED);
                    assertThat(exception.getMessage()).isEqualTo(expectedMessage);
                });
    }

    // 1번째 어트랙션 30(13:00), 2번째 어트랙션 10(14:00), 3번째 어트랙션 20(15:00)
    private RouteRecommendation threeStopRecommendation(Long ownerId) {
        return recommendation(ownerId, List.of(
                stop(1, 30L, VISIT_DATE.atTime(13, 0)),
                stop(2, 10L, VISIT_DATE.atTime(14, 0)),
                stop(3, 20L, VISIT_DATE.atTime(15, 0))), List.of());
    }

    private RouteRecommendation recommendation(Long ownerId, List<RouteRecommendationStop> stops,
                                               List<RouteRecommendationExclusion> exclusions) {
        RouteRecommendation recommendation = RouteRecommendation.create(ownerId, TICKET_ID,
                VISIT_DATE.atTime(12, 0), VISIT_DATE.atTime(22, 0), stops, exclusions);
        ReflectionTestUtils.setField(recommendation, "id", RECOMMENDATION_ID);
        return recommendation;
    }

    private RouteRecommendationStop stop(int sequence, Long attractionId, LocalDateTime slotStartAt) {
        return RouteRecommendationStop.of(sequence, attractionId, slotIdOf(attractionId), "어트랙션" + attractionId,
                5, false, slotStartAt.minusMinutes(10), slotStartAt, slotStartAt.plusMinutes(10));
    }

    private static Long slotIdOf(Long attractionId) {
        return attractionId * 100;
    }

    private static Long reservationIdOf(Long slotId) {
        return slotId + 1;
    }

    // 슬롯 ID 규칙(어트랙션 ID × 100)으로 슬롯 시각을 되돌려 실제 예약 응답처럼 만든다.
    private CreateReservationResponse reservationResponse(Long slotId) {
        LocalDateTime startsAt = switch ((int) (slotId / 100)) {
            case 30 -> VISIT_DATE.atTime(13, 0);
            case 10 -> VISIT_DATE.atTime(14, 0);
            case 20 -> VISIT_DATE.atTime(15, 0);
            default -> VISIT_DATE.atTime(11, 0);
        };
        return new CreateReservationResponse(reservationIdOf(slotId), "R-" + slotId, TICKET_ID, slotId,
                "어트랙션" + slotId / 100, ReservationStatus.RESERVED, startsAt, startsAt.plusMinutes(10),
                Instant.parse("2026-11-11T03:00:00Z"));
    }
}