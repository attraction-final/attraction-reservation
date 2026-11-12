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

    // 경로 추천에 고를 수 있는 어트랙션: 운영 중이고 좌표가 등록된 어트랙션 (ID 순)
    // 운영하지 않으면 NOT_OPERATING,좌표가 없으면 LOCATION_NOT_FOUND 사유로 추천에서 제외되므로 화면이 미리 걸러낼 수 있게 한다.
    // 구간(attraction_paths) 데이터는 고른 조합에 따라 달라 여기서 보지 않는다. (구간이 없거나 비활성이면 그 방문 순서만 무효)
    // 좌표(l.latitude, l.longitude)도 같이 돌려줘서 화면이 지도에 마커를 찍을 수 있게 한다. (이미 join 한 테이블이라 쿼리는 그대로 1번)
    @Query("""
            select new com.example.attractionreservation.domain.routeRecommendation.dto.RecommendableAttractionResponse(
                a.id, a.name, l.latitude, l.longitude)
            from Attraction a, AttractionLocation l
            where l.attractionId = a.id
              and a.deletedAt is null
              and a.operationStatus = com.example.attractionreservation.domain.attraction.entity.enums.OperationStatus.OPERATING
            order by a.id
            """)
    List<RecommendableAttractionResponse> findRecommendableAttractions();
}