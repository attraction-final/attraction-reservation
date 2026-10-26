package com.example.attractionreservation.domain.reservation.service;

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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final UserService userService;
    private final TicketService ticketService;
    private final AttractionSlotService attractionSlotService;

    // 예약 생성
    // 동시성 해결 필요
    @Transactional
    public CreateReservationResponse createReservation(Long userId, CreateReservationRequest request) {

        /* 현재시간, 오늘날짜, 현재시각 */
        LocalDateTime now = KstTime.now();
        LocalDate today = now.toLocalDate();

        /* 놀이공원 폐장시간 */
        LocalTime parkCloseTime = LocalTime.of(22, 0);

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

        // 티켓 입장 날짜
        if (!ticket.getValidDate().equals(today)) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_DATE_MISMATCH);
        }

        // 티켓 날짜와 슬롯 날짜 검증
        AttractionSlot attractionSlot = attractionSlotService.findAttractionSlotById(request.slotId());

        /* 슬롯 시작, 종료 시간 */
        LocalDateTime slotStartsAt = attractionSlot.getStartsAt();
        LocalDateTime slotEndsAt = attractionSlot.getEndsAt();

        /* 슬롯 예약 마감 시간 */
        LocalDateTime deadLine = attractionSlot.getStartsAt().minusMinutes(30);

        if (!slotStartsAt.toLocalDate().equals(ticket.getValidDate())) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_DATE_MISMATCH);
        }

        // 티켓 시간이랑 슬롯 시간 검증
        if (slotStartsAt.toLocalTime().isBefore(ticket.getType().getAvailableFrom())
                || slotStartsAt.toLocalTime().isAfter(parkCloseTime) || slotEndsAt.toLocalTime().isAfter(parkCloseTime)
        ) {
            throw new BusinessException(ErrorCode.TICKET_RESERVATION_TIME_NOT_ALLOWED);
        }

        // 어트랙션이 운영 중인지 확인
        if (!attractionSlot.getAttraction().isOperating()) {
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

        // 잔여 정원을 원자적으로 확보
        attractionSlotService.tryReservationCapacity(request.slotId());

        Reservation reservation = new Reservation(user, ticket, attractionSlot);
        Reservation savedReservation = reservationRepository.save(reservation);

        return CreateReservationResponse.from(savedReservation);
    }

    // 내 예약 목록 조회 (예약상태 + 날짜 + 페이징)
    public Page<SearchReservationResponse> findReservationAll(Long userId, Pageable stablePageable, SearchReservationCondition condition) {
        LocalDateTime dayStart;
        LocalDateTime nextDayStart;

        // 날짜가 null일 경우 메서드 호출시 npe 방지
        if (condition.date() == null) {
            dayStart = null;
            nextDayStart = null;
        } else {
            dayStart = condition.date().atStartOfDay();
            nextDayStart = condition.date().plusDays(1).atStartOfDay();
        }

        Page<Reservation> reservations = reservationRepository.findReservationAllByCondition(
                userId,
                condition.status(),
                dayStart,
                nextDayStart,
                stablePageable
        );

        return reservations.map(SearchReservationResponse::from);
    }

    // 내 예약 상세 조회
    public SearchReservationDetailResponse findReservationById(Long userId, Long reservationId) {
        Reservation reservation = reservationRepository.findReservationByIdAndUser_Id(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        return SearchReservationDetailResponse.from(reservation);
    }

    // 내 예약 취소
    // 추후 동시성 해결 필요
    @Transactional
    public UpdateReservationCancelResponse cancelReservation(Long userId, Long reservationId) {

        Reservation reservation = reservationRepository.findReservationByIdAndUser_Id(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        // 예약 상태가 RESERVED인지 확인
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        // 현재 KST 시각이 슬롯 시작 30분 전 이내인지 확인
        LocalDateTime currentTime = KstTime.now();
        Instant now = currentTime.atZone(KstTime.ZONE).toInstant();

        LocalDateTime deadLine = reservation.getAttractionSlot().getStartsAt().minusMinutes(30);

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
        LocalDateTime dayStart;
        LocalDateTime nextDayStart;

        // 날짜가 null일 경우 메서드 호출시 npe 방지
        if (condition.date() == null) {
            dayStart = null;
            nextDayStart = null;
        } else {
            dayStart = condition.date().atStartOfDay();
            nextDayStart = condition.date().plusDays(1).atStartOfDay();
        }

        Page<Reservation> reservations = reservationRepository.findAdminReservationByCondition(
                condition.userId(),
                condition.attractionId(),
                condition.status(),
                dayStart,
                nextDayStart,
                stablePageable
        );

        return reservations.map(SearchAdminReservationResponse::from);
    }

    // 관리자 예약 상세 조회
    public SearchAdminReservationDetailResponse findAdminReservationById(Long reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

        return SearchAdminReservationDetailResponse.from(reservation);
    }
}
