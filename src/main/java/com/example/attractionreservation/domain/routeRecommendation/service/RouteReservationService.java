package com.example.attractionreservation.domain.routeRecommendation.service;

import com.example.attractionreservation.domain.reservation.dto.CreateReservationRequest;
import com.example.attractionreservation.domain.reservation.dto.CreateReservationResponse;
import com.example.attractionreservation.domain.reservation.service.ReservationService;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteReservationResponse;
import com.example.attractionreservation.domain.routeRecommendation.dto.CreateRouteReservationResponse.ReservedStop;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendation;
import com.example.attractionreservation.domain.routeRecommendation.entity.RouteRecommendationStop;
import com.example.attractionreservation.domain.routeRecommendation.repository.RouteRecommendationRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


//저장된 추천 경로의 방문을 한 번에 예약한다(일괄 예약). 전부 성공해야 예약이 남는다
//트랜잭션: createRouteReservations는 하나의 트랜잭션이고, 방문마다 호출하는
//ReservationService ,createReservation(전파 REQUIRED)이 모두 이 트랜잭션에 참여한다.
//그래서 어느 방문에서든 예외가 나가면 앞서 만든 예약과 슬롯 정원 차감이 함께 롤백된다.
//내부 호출이 예외로 끝나면 참여 중인 트랜잭션이 rollback-only로 표시돼 더는 커밋할 수 없으므로,
//실패를 잡아 건너뛰고 계속 진행하지 않는다. 실패 원인만 {@link ErrorCode#ROUTE_RESERVATION_FAILED}로 바꿔 다시 던진다.
//처리 순서: 방문 순서가 아니라 어트랙션 ID 오름차순으로 예약한다. {@code createReservation}은
//슬롯 → 어트랙션 순서로 비관적 잠금을 거는데, 여러 일괄 예약이 동시에 돌 때 잠금 순서를 같게 해 교착을 피한다.
//응답의 예약 목록은 방문 순서(sequence 오름차순)로 정렬한다.

@Service
@RequiredArgsConstructor
public class RouteReservationService {

    private static final String FAILURE_MESSAGE = "%d번째 방문 '%s' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: %s";

    private final RouteRecommendationRepository routeRecommendationRepository;
    private final ReservationService reservationService;

    @Transactional
    public CreateRouteReservationResponse createRouteReservations(Long userId, Long recommendationId) {
        RouteRecommendation recommendation = routeRecommendationRepository.findDetailById(recommendationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_RECOMMENDATION_NOT_FOUND));
        if (!recommendation.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.NOT_ROUTE_OWNER);
        }

        // 추천의 방문 목록에는 제외된 어트랙션이 들어 있지 않으므로 PARTIAL도 방문만 예약된다.
        List<RouteRecommendationStop> stopsInLockOrder = recommendation.getStops().stream()
                .sorted(Comparator.comparing(RouteRecommendationStop::getAttractionId))
                .toList();

        List<ReservedStop> reservedStops = new ArrayList<>();
        for (RouteRecommendationStop stop : stopsInLockOrder) {
            CreateReservationResponse reservation = reserve(userId, recommendation.getTicketId(), stop);
            reservedStops.add(ReservedStop.of(stop, reservation));
        }
        return CreateRouteReservationResponse.of(recommendation.getId(), recommendation.getTicketId(), reservedStops);
    }

    // 예약 실패를 어느 방문에서 왜 실패했는지 담은 예외로 바꿔 던진다 그 밖의 예외(서버 오류 등)는 그대로 둔다
    private CreateReservationResponse reserve(Long userId, Long ticketId, RouteRecommendationStop stop) {
        try {
            return reservationService.createReservation(
                    userId, new CreateReservationRequest(ticketId, stop.getAttractionSlotId()));
        } catch (BusinessException exception) {
            throw routeReservationFailed(stop, exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            // 같은 슬롯 예약이 동시에 저장돼 예약 유일 제약을 위반한 경우다.
            throw routeReservationFailed(stop, ErrorCode.RESERVATION_DUPLICATED.getMessage());
        } catch (PessimisticLockingFailureException exception) {
            // 잠금 대기 시간 초과·교착. 전역 핸들러로 나가면 실패한 방문 정보 없이 CONCURRENT_UPDATE_CONFLICT가 되므로 같은 코드로 통일한다.
            throw routeReservationFailed(stop, ErrorCode.CONCURRENT_UPDATE_CONFLICT.getMessage());
        }
    }

    private BusinessException routeReservationFailed(RouteRecommendationStop stop, String reason) {
        return new BusinessException(ErrorCode.ROUTE_RESERVATION_FAILED,
                FAILURE_MESSAGE.formatted(stop.getSequenceNumber(), stop.getAttractionNameSnapshot(), reason));
    }
}