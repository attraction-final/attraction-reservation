package com.example.attractionreservation.domain.routeRecommendation.repository;

import com.example.attractionreservation.domain.routeRecommendation.entity.AttractionLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AttractionLocationRepository extends JpaRepository<AttractionLocation, Long> {

    // 선택한 어트랙션들의 좌표를 쿼리 1번으로 가져온다. 좌표가 없는 어트랙션은 결과에서 빠진다.
    List<AttractionLocation> findAllByAttractionIdIn(Collection<Long> attractionIds);
}
