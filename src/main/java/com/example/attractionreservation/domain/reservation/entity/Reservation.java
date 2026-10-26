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
import java.time.LocalDateTime;
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

    // 취소 요청
    public void cancelReservation(Instant now) {
        if (this.status != ReservationStatus.RESERVED) {
            throw new BusinessException(ErrorCode.RESERVATION_CANCEL_NOT_ALLOWED_STATUS);
        }

        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
    }
}
