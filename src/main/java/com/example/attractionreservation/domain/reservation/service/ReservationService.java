package com.example.attractionreservation.domain.reservation.service;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.service.AttractionSlotService;
import com.example.attractionreservation.domain.reservation.dto.*;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.service.TicketService;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.service.UserService;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import com.example.attractionreservation.global.util.KstTime;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private static final LocalTime PARK_CLOSE_TIME = LocalTime.of(22, 0);
    private static final long RESERVATION_DEADLINE_MINUTES = 30;

    private final ReservationRepository reservationRepository;
    private final UserService userService;
    private final TicketService ticketService;
    private final AttractionSlotService attractionSlotService;
    // 예약 생성의 구간별 처리 시간을 기록할 Timer를 등록하고 관리한다.
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 예약 생성
    @Transactional
    public CreateReservationResponse createReservation(Long userId, CreateReservationRequest request) {

        // 회원이 활성 상태인지 확인
        User user = userService.findByUserId(userId);
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE);
        }

        // 티켓의 소유자 확인
        Ticket ticket = ticketService.findByTicketId(request.ticketId());

        Long ticketUserId = ticket.getOrderItem().getOrder().getUser().getId();
        if (!ticketUserId.equals(user.getId())) {
            throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        }

        // 티켓 상태
        if (ticket.getStatus() != TicketStatus.IN_USE) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_NOT_ALLOWED_STATUS);
        }

        // 슬롯 잠금 조회: 잠금 대기뿐 아니라 SQL 실행과 엔티티 조회 시간도 포함한다.
        AttractionSlot attractionSlot;
        Timer.Sample sample = Timer.start(meterRegistry);
        boolean isFind = false;
        try {
            attractionSlot = attractionSlotService.findSlot(request.slotId());
            isFind = true;

        } finally {
            // 조회가 예외로 끝나도 시간을 기록한다. 측정 종료가 DB 잠금 해제를 의미하지는 않는다.
            // 이름에 해당하는 Timer를 가져와 이번 경과 시간과 호출 횟수를 누적한다.
            sample.stop(meterRegistry.timer("reservation.create.slot.lookup", "result", isFind ? "success" : "failure"));
        }
        // 조회 시간 기록이 끝난 직후부터 트랜잭션 완료 callback까지의 후속 처리 시간을 측정한다.
        // 실제 DB 잠금 획득·해제 시각과 일치하지 않으며, 앞선 Timer 기록 구간은 포함하지 않는다.
        Timer.Sample postLookupSample = Timer.start(meterRegistry);
        // 여기서는 callback만 등록한다. 메서드 반환 후 flush·commit 또는 rollback이 끝나면 실행된다.
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {

                    @Override
                    public void afterCompletion(int status) {
                        // 슬롯 조회 성공 여부와 별개로, 최종 트랜잭션 결과별 시간을 구분한다.
                        String result;
                        if (status == TransactionSynchronization.STATUS_COMMITTED) {
                            result = "committed";
                        } else if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            result = "rolledback";
                        } else {
                            result = "unknown";
                        }
                        // 예약 저장 호출이 정상 반환해도 최종 rollback이면 rolledback으로 기록한다.
                        postLookupSample.stop(meterRegistry.timer("reservation.create.slot.postlookup", "result", result));
                    }
                }
        );
        // 후속 구간 1: 슬롯 잠금을 보유한 채 어트랙션 잠금 조회를 수행한다.
        Attraction attraction;
        Timer.Sample attractionlookupSample = Timer.start(meterRegistry);
        boolean isAttraction = false;
        try {
            attraction = attractionSlotService.findAttractionByIdForUpdate(
                    attractionSlot.getAttraction().getId()
            );
            isAttraction = true;
        } finally {
            attractionlookupSample.stop(meterRegistry.timer("reservation.create.attraction.lookup", "result", isAttraction ? "success" : "failure"));
        }


        // 후속 구간 2: 필요한 잠금 조회가 끝난 뒤 기준 시각을 구하고 예약 가능 조건을 검증한다.
        /* 현재시간, 오늘날짜, 현재시각 */
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();

        // 티켓 입장 날짜
        if (!ticket.getValidDate().equals(today)) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_DATE_MISMATCH);
        }

        /* 슬롯 시작, 종료 시간 */
        LocalDateTime slotStartsAt = attractionSlot.getStartsAt();
        LocalDateTime slotEndsAt = attractionSlot.getEndsAt();

        /* 슬롯 예약 마감 시간 */
        LocalDateTime deadLine = slotStartsAt.minusMinutes(RESERVATION_DEADLINE_MINUTES);

        if (!slotStartsAt.toLocalDate().equals(ticket.getValidDate())) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_DATE_MISMATCH);
        }

        // 티켓 시간이랑 슬롯 시간 검증
        if (slotStartsAt.toLocalTime().isBefore(ticket.getType().getAvailableFrom())
                || slotStartsAt.toLocalTime().isAfter(PARK_CLOSE_TIME) || slotEndsAt.toLocalTime().isAfter(PARK_CLOSE_TIME)
        ) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_TIME_NOT_ALLOWED);
        }

        // 어트랙션이 운영 중인지 확인
        if (!attraction.isOperating()) {
            throw new BusinessException(ErrorCode.ATTRACTION_NOT_OPERATING);
        }

        // 슬롯이 OPEN 상태이며 예약 마감 시각 이내인지 확인
        if (attractionSlot.getStatus() != SlotStatus.OPEN) {
            throw new BusinessException(ErrorCode.SLOT_RESERVATION_NOT_ALLOWED_STATUS);
        }
        if (deadLine.isBefore(now)) {
            throw new BusinessException(ErrorCode.RESERVATION_DEADLINE_PASSED);
        }

        // 같은 회원의 동일 슬롯 활성 예약 중복 확인
        boolean isReserved = reservationRepository.existsReservedByUser_IdAndSlot_Id(userId, request.slotId(), ReservationStatus.RESERVED);
        if (isReserved) {
            throw new BusinessException(ErrorCode.RESERVATION_DUPLICATED);
        }

        // 같은 회원의 다른 활성 예약과 탑승 시간이 겹치는지 확인
        boolean isConflict = reservationRepository.existsTimeConflictReservation(
                userId,
                slotStartsAt,
                slotEndsAt,
                ReservationStatus.RESERVED
        );
        if (isConflict) {
            throw new BusinessException(ErrorCode.RESERVATION_TIME_CONFLICT);
        }

        // 후속 구간 3: 정원 확보 UPDATE와 슬롯 refresh·자동 마감 상태 동기화를 수행한다.
        // 잔여 정원을 원자적으로 확보
        attractionSlotService.tryReservationCapacity(request.slotId());

        // 후속 구간 4: 예약을 저장하고 응답을 만든다. 메서드 반환이 트랜잭션 완료는 아니다.
        Reservation reservation = new Reservation(user, ticket, attractionSlot);
        Reservation savedReservation = reservationRepository.save(reservation);

        return CreateReservationResponse.from(savedReservation);
    }

    // 내 예약 목록 조회 (예약상태 + 날짜 + 페이징)
    public Page<SearchReservationResponse> findReservationAll(Long userId, Pageable stablePageable, SearchReservationCondition condition) {

        ReservationDateRange dateRange = ReservationDateRange.from(condition.date());

        Page<Reservation> reservations = reservationRepository.findReservationAllByCondition(
                userId,
                condition.status(),
                dateRange.dayStart,
                dateRange.nextDayStart,
                stablePageable
        );

        return reservations.map(SearchReservationResponse::from);
    }

    // 내 예약 상세 조회
    public SearchReservationDetailResponse findReservationById(Long userId, Long reservationId) {
        Reservation reservation = getReservationByIdAndUserId(reservationId, userId);
        return SearchReservationDetailResponse.from(reservation);
    }

    // 내 예약 취소
    // 추후 동시성 해결 필요
    @Transactional
    public UpdateReservationCancelResponse cancelReservation(Long userId, Long reservationId) {

        Reservation reservation = reservationRepository.findByIdAndUserIdWithLock(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        // 예약 상태가 RESERVED인지 확인
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        // 현재 KST 시각이 슬롯 시작 30분 전 이내인지 확인
        LocalDateTime currentTime = KstTime.now();
        Instant now = currentTime.atZone(KstTime.ZONE).toInstant();

        LocalDateTime deadLine = reservation.getAttractionSlot().getStartsAt()
                .minusMinutes(RESERVATION_DEADLINE_MINUTES);

        if (currentTime.isAfter(deadLine)) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
        }

        // 취소 성공 시 예약 상태를 CANCELLED로 변경하고 취소 시각 기록
        reservation.cancelReservation(now);

        // 슬롯 예약 인원을 1 감소시켜 잔여 정원 복구
        Long slotId = reservation.getAttractionSlot().getId();
        attractionSlotService.releaseReservationCapacity(slotId);

        // 관리자가 직접 마감하거나 운영 중단·취소한 슬롯은 자동으로 열지 않음

        return UpdateReservationCancelResponse.from(reservation);
    }

    // 관리자 예약 목록 조회
    public Page<SearchAdminReservationResponse> findAdminReservationAll(
            SearchAdminReservationCondition condition,
            Pageable stablePageable
    ) {
        ReservationDateRange dateRange = ReservationDateRange.from(condition.date());

        Page<Reservation> reservations = reservationRepository.findAdminReservationByCondition(
                condition.userId(),
                condition.attractionId(),
                condition.status(),
                dateRange.dayStart,
                dateRange.nextDayStart,
                stablePageable
        );

        return reservations.map(SearchAdminReservationResponse::from);
    }

    // 관리자 예약 상세 조회
    public SearchAdminReservationDetailResponse findAdminReservationById(Long reservationId) {
        Reservation reservation = getReservationById(reservationId);
        return SearchAdminReservationDetailResponse.from(reservation);
    }

    // 관리자 예약 강제 취소
    @Transactional
    public UpdateAdminReservationCancelResponse adminReservationCancel(Long reservationId, UpdateAdminReservationCancelRequest request) {

        // 예약 존재 여부 및 RESERVED 상태 검증
        Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        // 취소 상태·시각·사유
        reservation.adminCancelReservation(request.cancelReason());

        // 슬롯 예약 인원을 1 감소시켜 잔여 정원 복구
        Long slotId = reservation.getAttractionSlot().getId();
        attractionSlotService.releaseReservationCapacity(slotId);

        return UpdateAdminReservationCancelResponse.from(reservation);
    }

    // 관리자 예약 상태 변경
    @Transactional
    public UpdateAdminReservationStatusResponse updateReservationStatus(Long reservationId, UpdateAdminReservationStatusRequest request) {
        Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        // KST 기준 슬롯 종료 이후인지 검증
        Instant now = Instant.now();
        LocalDateTime currentTime = LocalDateTime.ofInstant(now, KstTime.ZONE);
        LocalDateTime endTime = reservation.getAttractionSlot().getEndsAt();
        if (currentTime.isBefore(endTime)) {
            throw new BusinessException(ErrorCode.RESERVATION_SLOT_NOT_ENDED);
        }

        reservation.updateReservationStatus(request.status());

        // DB 반영 및 Auditing의 updatedAt 갱신
        reservationRepository.flush();

        return UpdateAdminReservationStatusResponse.from(reservation);
    }

    // reservation 공통 처리
    private Reservation getReservationById(Long reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION)
        );
    }

    // 본인 예약 조회 중복 메서드 분리
    private Reservation getReservationByIdAndUserId(Long reservationId, Long userId) {
        return reservationRepository.findReservationByIdAndUser_Id(reservationId, userId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION)
        );
    }

    // 목록 조회 날짜 범위 계산 분리
    private record ReservationDateRange(LocalDateTime dayStart, LocalDateTime nextDayStart) {
        private static ReservationDateRange from(LocalDate date) {
            if (date == null) {
                return new ReservationDateRange(null, null);
            }

            return new ReservationDateRange(
                    date.atStartOfDay(),
                    date.plusDays(1).atStartOfDay()
            );
        }
    }

}
