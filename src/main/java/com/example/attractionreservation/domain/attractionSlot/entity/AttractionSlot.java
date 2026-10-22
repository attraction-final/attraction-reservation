package com.example.attractionreservation.domain.attractionSlot.entity;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.UnavailableReason;
import com.example.attractionreservation.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@Entity
@Table(name = "attraction_slots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttractionSlot extends BaseTimeEntity {

    public static final LocalTime PARK_OPEN_TIME = LocalTime.of(10, 0);   // 공원 개장 (BR-SLT-03)
    public static final LocalTime PARK_CLOSE_TIME = LocalTime.of(22, 0);  // 공원 폐장 (BR-SLT-03)
    public static final long RESERVATION_DEADLINE_MINUTES = 30L;          // 예약 마감 = 시작 30분 전

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 슬롯 N개 : 어트랙션 1개. LAZY = 실제로 필요할 때만 어트랙션을 DB에서 읽음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attraction_id", nullable = false)
    private Attraction attraction;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;

    @Column(name = "capacity", nullable = false)
    private Integer capacity;

    @Column(name = "reserved_count", nullable = false)
    private Integer reservedCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SlotStatus status;

    // 슬롯 등록
    public static AttractionSlot create(Attraction attraction, LocalDateTime startsAt, Integer capacity) {
        AttractionSlot slot = new AttractionSlot();
        slot.attraction = attraction;
        slot.startsAt = startsAt;
        slot.endsAt = calculateEndsAt(startsAt, attraction.getDurationMinutes()); // BR-SLT-01
        slot.capacity = capacity;
        slot.reservedCount = 0;
        slot.status = SlotStatus.OPEN;
        return slot;
    }

    // 종료 시각 = 시작 시각 + 어트랙션 탑승 소요 시간 (BR-SLT-01)
    public static LocalDateTime calculateEndsAt(LocalDateTime startsAt, Integer durationMinutes) {
        return startsAt.plusMinutes(durationMinutes);
    }

    // 시작~종료가 같은 날 10:00~22:00 안에 있는지 (BR-SLT-03)
    public static boolean isWithinOperatingHours(LocalDateTime startsAt, LocalDateTime endsAt) {
        boolean isSameDay = startsAt.toLocalDate().equals(endsAt.toLocalDate());
        boolean isAfterOpen = !startsAt.toLocalTime().isBefore(PARK_OPEN_TIME);
        boolean isBeforeClose = !endsAt.toLocalTime().isAfter(PARK_CLOSE_TIME);
        return isSameDay && isAfterOpen && isBeforeClose;
    }

    // 사용자 날짜별 조회
    // 지금 예약할 수 없는 이유를 돌려준다. 예약 가능하면 null.
    public UnavailableReason findUnavailableReason(boolean isAttractionOperating, LocalDateTime now) {
        if (!isAttractionOperating) {
            return UnavailableReason.ATTRACTION_NOT_OPERATING;
        }
        if (status == SlotStatus.SUSPENDED) {
            return UnavailableReason.SUSPENDED;
        }
        if (now.isAfter(getReservationDeadline())) { // 마감 시각 정각은 허용 (9장 #2)
            return UnavailableReason.DEADLINE_PASSED;
        }
        if (status == SlotStatus.CLOSED) {
            return UnavailableReason.CLOSED;
        }
        if (isFull()) {
            return UnavailableReason.FULL;
        }
        return null;
    }

    // 조회용 계산
    public int getRemainingCount() {
        return capacity - reservedCount;
    }

    public LocalDateTime getReservationDeadline() {
        return startsAt.minusMinutes(RESERVATION_DEADLINE_MINUTES);
    }

    public boolean isFull() {
        return getRemainingCount() <= 0;
    }

    public boolean hasReservations() {
        return reservedCount > 0;
    }

    public boolean isCancelled() {
        return status == SlotStatus.CANCELLED;
    }

    // 종료 시각이 지났으면(같은 시각 포함) 종료된 슬롯
    public boolean isEnded(LocalDateTime now) {
        return !now.isBefore(endsAt);
    }

    // AttractionSlot service의 시간 수정에 필요
    public void changeTime(LocalDateTime newStartsAt) {
        this.startsAt = newStartsAt;
        this.endsAt = calculateEndsAt(newStartsAt, attraction.getDurationMinutes());
    }
}
