package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

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
}
