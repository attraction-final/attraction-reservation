package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;
//후보 선정을 통과한 어트랙션 한 건과 사용 가능한 후보 슬롯 목록
//이동 시간은 여기 두지 않는다. 첫 구간은 현재 위치 추정값(StartLeg), 이후 구간은 attraction_paths(PathEdge)만 쓴다
public record CandidateAttraction(
        Long attractionId,
        String attractionName,
        List<CandidateSlot> slots //후보 선정 조건을 통과한 슬롯 목록
) {
}