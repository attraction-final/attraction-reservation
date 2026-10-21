package com.example.attractionreservation.domain.attractionSlot.entity;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
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
}
