package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AttractionPathRepository extends JpaRepository<AttractionPath, Long> {


//선택한 어트랙션끼리의 이동 구간을 쿼리 1번으로 모두 가져온다. (5개 선택 시 최대 5 × 4 = 20행)
//방문 순서를 계산할 때마다 DB를 조회하지 않도록, 서비스는 이 결과로 메모리 조회표(TravelTimeTable)를 만든다.
//비활성(is_active = false) 구간도 함께 가져온다. 호출자가 활성 구간만 골라 조회표를 만들어야 한다.
//uk_attraction_paths_from_to (from_attraction_id, to_attraction_id) 인덱스를 사용한다.

    @Query("""
            select p from AttractionPath p
            where p.fromAttractionId in :attractionIds
              and p.toAttractionId in :attractionIds
            """)
    List<AttractionPath> findAllBetween(@Param("attractionIds") Collection<Long> attractionIds);
}
