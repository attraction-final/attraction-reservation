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

    // 필드 추가
    // true  = 잔여 정원이 0이 되어 시스템이 자동으로 마감함 → 정원이 생기면 자동으로 다시 OPEN
    // false = 그 외 (관리자가 직접 CLOSED로 바꾼 경우 포함) → 자동으로 다시 열지 않음
    @Column(name = "auto_closed", nullable = false)
    private boolean autoClosed;

    // 슬롯 등록
    public static AttractionSlot create(Attraction attraction, LocalDateTime startsAt, Integer capacity) {
        AttractionSlot slot = new AttractionSlot();
        slot.attraction = attraction;
        slot.startsAt = startsAt;
        slot.endsAt = calculateEndsAt(startsAt, attraction.getDurationMinutes()); // BR-SLT-01
        slot.capacity = capacity;
        slot.reservedCount = 0;
        slot.status = SlotStatus.OPEN;
        slot.autoClosed = false; // 새 슬롯은 마감 상태가 아님
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
    public UnavailableReason findUnavailableReason(boolean attractionOperating, LocalDateTime now) {
        // 어트랙션 자체가 운영 안 함
        if (!attractionOperating) {
            return UnavailableReason.ATTRACTION_NOT_OPERATING;
        }
        // 이 슬롯만 운영 중단
        if (status == SlotStatus.SUSPENDED) {
            return UnavailableReason.SUSPENDED;
        }
        // 예약 마감 시각(시작 30분 전)이 지남
        if (now.isAfter(startsAt.minusMinutes(30))) {
            return UnavailableReason.DEADLINE_PASSED;
        }
        // 자리가 없음 (CLOSED 상태도 여기로 모음)
        if (status == SlotStatus.CLOSED || getRemainingCount() <= 0) {
            return UnavailableReason.FULL;
        }
        // 아무 문제 없음: 예약 가능
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

    // AttractionSlot service의 정원 수정에 필요
    public void changeCapacity(Integer newCapacity) {
        this.capacity = newCapacity;
        syncStatusWithRemainingCount(); // BR-SLT-07
    }

    // 잔여 0이면 OPEN → CLOSED, 잔여 1 이상이면 CLOSED → OPEN (SUSPENDED는 그대로)
    private void syncStatusWithRemainingCount() {
        if (status == SlotStatus.OPEN && isFull()) {
            this.status = SlotStatus.CLOSED;
            this.autoClosed = true;  // "정원 때문에 닫았다"고 기록
        } else if (status == SlotStatus.CLOSED && autoClosed && !isFull()) {
            this.status = SlotStatus.OPEN;
            this.autoClosed = false; // 다시 열었으니 표시 지움
        }
    }

    // AttractionSlot service의 상태 변경에 필요
    public void changeStatus(SlotStatus newStatus) {
        this.status = newStatus;
        this.autoClosed = false; // 관리자의 의도가 우선 → 자동 재오픈 대상에서 빠짐
    }

    // AttractionSlot service의 삭제에 필요
    public void cancel() {
        this.status = SlotStatus.CANCELLED;
        this.autoClosed = false;
    }

}
