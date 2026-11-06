package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// JpaRepository: 기본 CRUD + 이름으로 만드는 쿼리(Derived Query)
// AttractionRepositoryCustom: QueryDSL로 직접 만든 복잡한 쿼리
// → 서비스에서는 attractionRepository 하나로 둘 다 사용할 수 있다.
public interface AttractionRepository extends JpaRepository<Attraction, Long>, AttractionRepositoryCustom {

    // 등록 시 이름 중복 확인
    boolean existsByNameAndDeletedAtIsNull(String name);

    // 삭제되지 않은 어트랙션만 단건 조회
    Optional<Attraction> findByIdAndDeletedAtIsNull(Long id);

    // 수정 시 이름 중복 확인 (자기 자신은 제외)
    boolean existsByNameAndIdNotAndDeletedAtIsNull(String name, Long id);

    // ★ 추가: 슬롯 등록·시간 수정 시 같은 어트랙션의 겹침 검사를 한 명씩 하도록 어트랙션 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attraction a WHERE a.id = :attractionId AND a.deletedAt IS NULL")
    Optional<Attraction> findActiveByIdForUpdate(@Param("attractionId") Long attractionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // 슬롯 자동 생성 대상: 삭제되지 않은 어트랙션 ID 전체 (ID 순)
    @Query("SELECT a.id FROM Attraction a WHERE a.deletedAt IS NULL ORDER BY a.id")
    List<Long> findAllActiveIds();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Attraction a WHERE a.id = :attractionId")
    Optional<Attraction> findByIdForUpdate(@Param("attractionId") Long attractionId);

    //Q&A 분류 대상 조회 선택지 순서가 jev 결과에 영향을 줄 수 있어 id 순으로 고정한다. -> 위치 편향 영향을 최대한? 안받기 위해
    List<Attraction> findAllByDeletedAtIsNullOrderByIdAsc();
}