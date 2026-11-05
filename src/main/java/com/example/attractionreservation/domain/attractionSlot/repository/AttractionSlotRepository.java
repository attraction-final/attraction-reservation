package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import com.example.attractionreservation.domain.attractionSlot.entity.enums.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
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

    // 슬롯 자동 생성 - 기간 안에 이미 있는 슬롯의 시작 시각 (취소된 슬롯 포함)
    // 시작 시각의 날짜만 보고 "이미 슬롯이 있는 날"을 판단한다.
    // (attraction_id, starts_at) 인덱스를 그대로 사용한다.
    @Query("""
    SELECT s.startsAt
      FROM AttractionSlot s
     WHERE s.attraction.id = :attractionId
       AND s.startsAt >= :from
       AND s.startsAt < :to
    """)
    List<LocalDateTime> findStartTimesByAttractionIdBetween(
            @Param("attractionId") Long attractionId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // 예약 인원을 복구하는 동안 같은 슬롯의 동시 변경을 보호한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AttractionSlot s WHERE s.id = :slotId")
    Optional<AttractionSlot> findByIdForUpdate(
            @Param("slotId") Long slotId
    );
}