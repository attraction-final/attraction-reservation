package com.example.attractionreservation.domain.ticket.entity;

import com.example.attractionreservation.domain.order.entity.OrderItem;
import com.example.attractionreservation.domain.ticket.entity.enums.TicketStatus;
import com.example.attractionreservation.domain.ticketProduct.entity.enums.TicketType;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import com.example.attractionreservation.global.exception.BusinessException;
import com.example.attractionreservation.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.*;
import java.util.UUID;

@Getter
@Entity
@Table(name = "tickets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ticket extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "valid_date", nullable = false)
    private LocalDate validDate;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TicketType type;

    @Column(name = "entered_at")
    private Instant enteredAt;

    @Column(name = "end_time")
    private Instant endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    public Ticket(LocalDate validDate, OrderItem orderItem) {
        if (validDate == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_VALID_DATE);
        }

        if (orderItem == null) {
            throw new BusinessException(ErrorCode.INVALID_TICKET_ORDER_ITEM);
        }

        this.validDate = validDate;
        this.code = "TKT-" + UUID.randomUUID().toString();
        this.status = TicketStatus.UNUSED;
        this.name = orderItem.getTicketProduct().getName();
        this.type = orderItem.getTicketProduct().getType();
        this.orderItem = orderItem;
    }

    // 티켓 입장 처리
    public void entryTicket(Instant now) {
        ZonedDateTime kstNow = now.atZone(ZoneId.of("Asia/Seoul"));

        validateEntryStatus();
        validateEntryDate(kstNow.toLocalDate());
        validateEntryTime(kstNow.toLocalTime());

        this.status = TicketStatus.IN_USE;
        this.enteredAt = now;
    }

    // 입장 상태 검증
    private void validateEntryStatus() {
        if (this.status != TicketStatus.UNUSED) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_NOT_ALLOWED_STATUS);
        }
    }

    // 입장권 날짜 검증
    private void validateEntryDate(LocalDate today) {
        if (!this.validDate.equals(today)) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_DATE_MISMATCH);
        }
    }

    // 입장권 시간 검증
    private void validateEntryTime(LocalTime currentTime) {
        LocalTime opensAt = switch (this.type) {
            case ALL_DAY -> LocalTime.of(10, 0);
            case AFTERNOON -> LocalTime.of(15, 0);
        };
        LocalTime closesAt = LocalTime.of(22, 0);

        if (currentTime.isBefore(opensAt) || !currentTime.isBefore(closesAt)) {
            throw new BusinessException(ErrorCode.TICKET_ENTRY_TIME_NOT_ALLOWED);
        }
    }
}
