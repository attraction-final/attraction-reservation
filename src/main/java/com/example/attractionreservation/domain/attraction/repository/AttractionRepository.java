package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
