package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AttractionSlotRepository
        extends JpaRepository<AttractionSlot, Long>,
        AttractionSlotRepositoryCustom {

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

    // 예약 인원을 복구하는 동안 같은 슬롯의 동시 변경을 보호한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AttractionSlot s WHERE s.id = :slotId")
    Optional<AttractionSlot> findByIdForUpdate(
            @Param("slotId") Long slotId
    );
}