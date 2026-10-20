package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AttractionRepository extends JpaRepository<Attraction, Long> {

    // 등록 시 이름 중복 확인
    boolean existsByNameAndDeletedAtIsNull(String name);

    // 어트랙션 목록 조회
    @Query(value = """
        SELECT a.*
          FROM attractions a
         WHERE a.deleted_at IS NULL
           AND (:keyword IS NULL OR a.name LIKE CONCAT('%', :keyword, '%'))
           AND (:operatingOnly = FALSE OR a.operation_status = 'OPERATING')
           AND (:reservableOnly = FALSE OR (
                    a.operation_status = 'OPERATING'
                AND EXISTS (SELECT 1
                              FROM attraction_slots s
                             WHERE s.attraction_id = a.id
                               AND s.status = 'OPEN'
                               AND s.capacity - s.reserved_count >= 1
                               AND s.starts_at >= :reservableFrom
                               AND s.starts_at < :todayEnd)))
         ORDER BY CASE WHEN :sort = 'LATEST' THEN a.created_at END DESC,
                  a.name ASC,
                  a.id ASC
        """,
            countQuery = """
        SELECT COUNT(*)
          FROM attractions a
         WHERE a.deleted_at IS NULL
           AND (:keyword IS NULL OR a.name LIKE CONCAT('%', :keyword, '%'))
           AND (:operatingOnly = FALSE OR a.operation_status = 'OPERATING')
           AND (:reservableOnly = FALSE OR (
                    a.operation_status = 'OPERATING'
                AND EXISTS (SELECT 1
                              FROM attraction_slots s
                             WHERE s.attraction_id = a.id
                               AND s.status = 'OPEN'
                               AND s.capacity - s.reserved_count >= 1
                               AND s.starts_at >= :reservableFrom
                               AND s.starts_at < :todayEnd)))
        """,
            nativeQuery = true)
    Page<Attraction> searchAttractions(@Param("keyword") String keyword,
                                       @Param("operatingOnly") boolean operatingOnly,
                                       @Param("reservableOnly") boolean reservableOnly,
                                       @Param("sort") String sort,
                                       @Param("reservableFrom") LocalDateTime reservableFrom,
                                       @Param("todayEnd") LocalDateTime todayEnd,
                                       Pageable pageable);

    /**
     * 여러 어트랙션의 "오늘 예약 가능한 슬롯"을 한 번에 조회 (N+1 방지)
     * 어트랙션별로 시작 시각이 빠른 순서로 정렬되어 나온다.
     */
    @Query(value = """
        SELECT s.id                           AS slotId,
               s.attraction_id                AS attractionId,
               s.starts_at                    AS startsAt,
               s.capacity - s.reserved_count  AS remainingCount
          FROM attraction_slots s
         WHERE s.attraction_id IN (:attractionIds)
           AND s.status = 'OPEN'
           AND s.capacity - s.reserved_count >= 1
           AND s.starts_at >= :reservableFrom
           AND s.starts_at < :todayEnd
         ORDER BY s.attraction_id, s.starts_at
        """, nativeQuery = true)
    List<ReservableSlotView> findReservableSlots(@Param("attractionIds") List<Long> attractionIds,
                                                 @Param("reservableFrom") LocalDateTime reservableFrom,
                                                 @Param("todayEnd") LocalDateTime todayEnd);

    // 삭제되지 않은 어트랙션만 단건 조회
    Optional<Attraction> findByIdAndDeletedAtIsNull(Long id);
}

