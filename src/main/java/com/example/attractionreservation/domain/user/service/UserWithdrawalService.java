package com.example.attractionreservation.domain.user.service;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.repository.AttractionSlotRepository;
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

@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final ReservationRepository reservationRepository;
    private final AttractionSlotRepository attractionSlotRepository;

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED
                ));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_WITHDRAWABLE);
        }

        // 미사용 입장권이 있으면 예약과 회원 상태를 변경하기 전에 중단한다.
        boolean hasUnusedTicket =
                ticketRepository.existsByOrderItem_Order_User_IdAndStatus(
                        userId,
                        TicketStatus.UNUSED
                );

        if (hasUnusedTicket) {
            throw new BusinessException(
                    ErrorCode.USER_WITHDRAWAL_UNUSED_TICKET
            );
        }

        Instant now = Instant.now();

        // 슬롯 시각은 LocalDateTime이므로 한국 기준 시각으로 비교한다.
        LocalDateTime nowInSeoul = LocalDateTime.ofInstant(
                now,
                ZoneId.of("Asia/Seoul")
        );

        var reservations = reservationRepository.findUpcomingForUpdate(
                userId,
                ReservationStatus.RESERVED,
                nowInSeoul
        );

        for (Reservation reservation : reservations) {
            Long slotId = reservation.getAttractionSlot().getId();

            AttractionSlot slot =
                    attractionSlotRepository.findByIdForUpdate(slotId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "예약에 연결된 슬롯을 찾을 수 없습니다."
                            ));

            reservation.cancelForWithdrawal(now);
            slot.releaseReservation();
        }

        // 예약 취소와 정원 복구가 모두 성공한 경우에만 탈퇴 상태로 변경한다.
        user.withdraw(now);

        // DB 제약 오류도 이 트랜잭션 안에서 확인한다.
        userRepository.flush();
    }
}