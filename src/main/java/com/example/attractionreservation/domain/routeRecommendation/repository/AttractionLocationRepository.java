package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse;
import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface AttractionLocationRepository extends JpaRepository<AttractionLocation, Long> {

    // 선택한 어트랙션들의 좌표를 쿼리 1번으로 가져온다. 좌표가 없는 어트랙션은 결과에서 빠진다.
    List<AttractionLocation> findAllByAttractionIdIn(Collection<Long> attractionIds);

    // 경로 추천에 고를 수 있는 어트랙션: 삭제되지 않았고 좌표가 등록된 어트랙션 (ID 순)
    // 좌표가 없으면 추천 요청이 ATTRACTION_LOCATION_NOT_FOUND(409)로 실패하므로 화면이 미리 걸러낼 수 있게 한다.
    @Query("""
            select new com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse(
                a.id, a.name)
            from Attraction a, AttractionLocation l
            where l.attractionId = a.id
              and a.deletedAt is null
            order by a.id
            """)
    List<RecommendableAttractionResponse> findRecommendableAttractions();
}
