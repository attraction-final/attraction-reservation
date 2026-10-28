package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;

//후보 선정 한 번의 전체 결과
public record CandidateSelection(
        //예약 조건을 만족하는 슬롯이 하나 이상 남은 어트랙션 목록
        List<CandidateAttraction> candidates,
        //후보 선정 과정에서 제외된 어트랙션과 이유 목록
        List<Exclusion> exclusions
) {
}
