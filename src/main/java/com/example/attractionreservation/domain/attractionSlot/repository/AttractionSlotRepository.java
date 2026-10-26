package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.time.LocalDateTime;
import java.util.List;

public interface AttractionSlotRepository extends JpaRepository<AttractionSlot, Long> {

    // 시간이 겹치는 슬롯 개수 (등록용)
    // 겹침 조건: 기존 시작 < 새 종료  AND  기존 종료 > 새 시작
    @Query(value = """
        SELECT COUNT(*)
          FROM attraction_slots s
         WHERE s.attraction_id = :attractionId
           AND s.status <> 'CANCELLED'
           AND s.starts_at < :endsAt
           AND s.ends_at > :startsAt
        """, nativeQuery = true)
    long countOverlappingSlots(@Param("attractionId") Long attractionId,
                               @Param("startsAt") LocalDateTime startsAt,
                               @Param("endsAt") LocalDateTime endsAt);

    // 특정 어트랙션의 하루치 슬롯 (시작 시각 빠른 순)
    // includeCancelled = false 이면 취소(CANCELLED)된 슬롯은 뺀다.
    @Query(value = """
        SELECT s.*
          FROM attraction_slots s
         WHERE s.attraction_id = :attractionId
           AND s.starts_at >= :dayStart
           AND s.starts_at < :nextDayStart
           AND (:includeCancelled = TRUE OR s.status <> 'CANCELLED')
         ORDER BY s.starts_at ASC, s.id ASC
        """, nativeQuery = true)
    List<AttractionSlot> findSlotsByAttractionIdAndDate(@Param("attractionId") Long attractionId,
                                                        @Param("dayStart") LocalDateTime dayStart,
                                                        @Param("nextDayStart") LocalDateTime nextDayStart,
                                                        @Param("includeCancelled") boolean includeCancelled);

    // 시간이 겹치는 슬롯 개수 (시간 수정용, 자기 자신은 제외)
    @Query(value = """
        SELECT COUNT(*)
          FROM attraction_slots s
         WHERE s.attraction_id = :attractionId
           AND s.id <> :excludeSlotId
           AND s.status <> 'CANCELLED'
           AND s.starts_at < :endsAt
           AND s.ends_at > :startsAt
        """, nativeQuery = true)
    long countOverlappingSlotsExcludingSlotId(@Param("attractionId") Long attractionId,
                                              @Param("excludeSlotId") Long excludeSlotId,
                                              @Param("startsAt") LocalDateTime startsAt,
                                              @Param("endsAt") LocalDateTime endsAt);

    // 예약 인원을 복구하는 동안 같은 슬롯의 동시 변경을 보호한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AttractionSlot s WHERE s.id = :slotId")
    Optional<AttractionSlot> findByIdForUpdate(
            @Param("slotId") Long slotId
    );
}
