package com.example.attractionreservation.domain.reservation.entity;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationStatus;
import com.example.attractionreservation.domain.ticket.entity.Ticket;
import com.example.attractionreservation.domain.user.entity.User;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "reservation_number", nullable = false, unique = true, length = 20)
    private String reservationNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attraction_slot_id", nullable = false)
    private AttractionSlot attractionSlot;

    public Reservation(User user, Ticket ticket, AttractionSlot attractionSlot) {
        if (user == null) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_USER);
        }

        if (ticket == null) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_TICKET);
        }

        if (attractionSlot == null) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_SLOT);
        }

        this.reservationNumber = "RSV-" + UUID.randomUUID().toString().substring(0, 16);
        this.user = user;
        this.ticket = ticket;
        this.attractionSlot = attractionSlot;
        this.status = ReservationStatus.RESERVED;
    }

    public void cancelForWithdrawal(Instant cancelledAt) {
        // 이미 완료·취소된 예약을 다시 변경하지 않는다.
        if (this.status != ReservationStatus.RESERVED) {
            throw new IllegalStateException(
                    "예약 상태가 RESERVED인 경우에만 탈퇴로 취소할 수 있습니다."
            );
        }

        if (cancelledAt == null) {
            throw new IllegalArgumentException("예약 취소 일시는 필수입니다.");
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = "회원 탈퇴";
        this.cancelledAt = cancelledAt;
    }
}
