package com.example.attractionreservation.domain.reservation.entity;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.reservation.entity.enums.ReservationCancelReason;
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
// 같은 회원·슬롯의 RESERVED 예약은 1건만 허용한다.
// 비활성 예약의 active_reservation은 NULL이므로 과거 이력은 여러 건 보존할 수 있다.
@Table(name = "reservations",
        uniqueConstraints = {
            @UniqueConstraint(
                name = "uk_reservations_user_slot_active",
                columnNames = {
                        "user_id",
                        "attraction_slot_id",
                        "active_reservation"
                }
            )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;

    // 활성 예약 중복 방지용 보조 컬럼: DB에서 RESERVED는 1, 나머지 상태는 NULL로 계산
    @Column(
            name = "active_reservation",
            insertable = false,
            updatable = false,
            columnDefinition = """
                    INT GENERATED ALWAYS AS (
                        CASE WHEN reservation_status = 'RESERVED' THEN 1 ELSE NULL END 
                    )
                    """
    )
    private Integer activeReservation;

    @Column(name = "cancel_reason", length = 255)
    @Enumerated(EnumType.STRING)
    private ReservationCancelReason cancelReason;

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

    // 취소 요청
    public void cancelReservation(Instant now) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
    }

    // 관리자 강제 취소 요청
    public void adminCancelReservation(ReservationCancelReason reason) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        if (reason == null) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_CANCEL_REASON);
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelReason = reason;
    }

    // 관리자 예약 상태 변경
    public void updateReservationStatus(ReservationStatus status) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (status != ReservationStatus.NO_SHOW && status != ReservationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_RESERVATION_TARGET_STATUS);
        }
        this.status = status;
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
        this.cancelReason = ReservationCancelReason.USER_WITHDRAWAL;
        this.cancelledAt = cancelledAt;
    }
}
