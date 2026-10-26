package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttractionSlotRepository extends JpaRepository<AttractionSlot, Long>, AttractionSlotRepositoryCustom {

    // 예약 자리 확보 - 업데이트 쿼리
    @Modifying
    @Query("""
    UPDATE AttractionSlot s
    SET s.reservedCount = s.reservedCount + 1
    WHERE s.id = :slotId
      AND s.status = :open
      AND s.reservedCount < s.capacity
    """)
    int tryReserveCapacity(
            @Param("slotId") Long slotId,
            @Param("open") SlotStatus status
    );

    // 예약 취소 - reservedCount - 1
    @Modifying
    @Query("""
    UPDATE AttractionSlot s
       SET s.reservedCount = s.reservedCount - 1 
     WHERE s.id = :slotId 
       AND s.reservedCount > 0
    """)
    int tryReleaseCapacity(@Param("slotId") Long slotId);
}
