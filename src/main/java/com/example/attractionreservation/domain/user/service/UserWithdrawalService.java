package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
import com.example.attractionreservation.domain.refund.entity.enums.RefundStatus;
import com.example.attractionreservation.domain.refund.repository.RefundRepository;
import com.example.attractionreservation.domain.reservation.entity.Reservation;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.reservation.repository.ReservationRepository;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticket.repository.TicketRepository;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.domain.user.entity.UserStatus;
import com.example.attractionreservation.domain.user.repository.UserRepository;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final ReservationRepository reservationRepository;
    private final AttractionSlotRepository attractionSlotRepository;
    private final RefundRepository refundRepository;

    // 환불 완료 전에는 탈퇴를 보류한다. FAILED는 재시도 대기, DEAD는 수동 확인 대상이므로 포함한다.
    private static final List<RefundStatus> WITHDRAWAL_BLOCKING_REFUND_STATUSES =
            List.of(
                    RefundStatus.REQUESTED,
                    RefundStatus.PROCESSING,
                    RefundStatus.PG_PENDING,
                    RefundStatus.FAILED,
                    RefundStatus.DEAD
            );

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED
                ));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_WITHDRAWABLE);
        }

        Instant now = Instant.now();

        // 입장권 날짜와 예약 시작 시각을 같은 한국 시각 기준으로 판단한다.
        LocalDateTime nowInSeoul = LocalDateTime.ofInstant(
                now,
                ZoneId.of("Asia/Seoul")
        );

        boolean hasUnusedTicket =
                ticketRepository
                        .existsByOrderItem_Order_User_IdAndStatusAndValidDateGreaterThanEqual(
                                userId,
                                TicketStatus.UNUSED,
                                nowInSeoul.toLocalDate()
                        );

        if (hasUnusedTicket) {
            throw new BusinessException(
                    ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET
            );
        }

        // 환불 요청 시 티켓이 이미 REFUNDED로 바뀌므로 UNUSED 검사만으로는 미완료 환불을 알 수 없다.
        // 티켓 상태·이용일 대신 해당 회원의 실제 환불 상태를 기준으로 확인한다.
        boolean hasIncompleteRefund =
                refundRepository.existsByPayment_Order_User_IdAndStatusIn(
                        userId,
                        WITHDRAWAL_BLOCKING_REFUND_STATUSES
                );

        // 예약 취소·정원 복구·회원 탈퇴를 시작하기 전에 거부하여 기존 데이터를 유지한다.
        if (hasIncompleteRefund) {
            throw new BusinessException(ErrorCode.USER_WITHDRAWAL_INCOMPLETE_REFUND);
        }

        // 이용 예정 예약의 후보 ID를 예약 ID 오름차순으로 조회한다.
        List<Long> reservationIds = reservationRepository.findUpcomingReservationIdsByUserId(
                userId,
                ReservationStatus.RESERVED,
                nowInSeoul
        );

        // 모든 대상 예약의 잠금을 먼저 확보한 뒤 슬롯을 처리한다.
        List<Reservation> reservationsToCancel = new ArrayList<>();

        for (Long reservationId : reservationIds) {
            Reservation reservation = reservationRepository.findByIdWithLock(reservationId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_RESERVATION));

            // 잠금을 기다리는 동안 다른 요청이 처리한 예약은 건너뛴다.
            if (reservation.getStatus() != ReservationStatus.RESERVED) {
                continue;
            }

            // 후보 조회와 동일하게 시작 시각이 기준 시각보다 이후인 예약만 처리한다.
            if (!reservation.getAttractionSlot().getStartsAt().isAfter(nowInSeoul)) {
                continue;
            }

            reservationsToCancel.add(reservation);
        }

        // 예약 잠금 확보 후, 슬롯 접근 순서도 일정하게 유지한다.
        reservationsToCancel.sort(
                Comparator.comparing((Reservation reservation) -> reservation.getAttractionSlot().getId())
                        .thenComparing(Reservation::getId)
        );

        for (Reservation reservation : reservationsToCancel) {
            Long slotId = reservation.getAttractionSlot().getId();

            AttractionSlot slot = attractionSlotRepository.findByIdForUpdate(slotId)
                    .orElseThrow(() -> new IllegalStateException("예약에 연결된 슬롯을 찾을 수 없습니다."));

            reservation.cancelForWithdrawal(now);
            slot.releaseReservation();
        }

        // 예약 취소와 정원 복구가 모두 성공한 경우에만 탈퇴 상태로 변경한다.
        user.withdraw(now);

        // DB 제약 오류도 이 트랜잭션 안에서 확인한다.
        userRepository.flush();
    }
}
