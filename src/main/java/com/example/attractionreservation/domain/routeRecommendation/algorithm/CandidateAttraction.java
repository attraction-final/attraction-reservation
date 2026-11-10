package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;
//후보 선정을 통과한 어트랙션 한 건과 사용 가능한 후보 슬롯 목록
public record CandidateAttraction(
        Long attractionId,
        String attractionName,
        int moveMinutes, //정문 → 이 어트랙션 이동 시간. 첫 방문이거나 실측 구간이 없을 때 대체값으로 쓴다
        List<CandidateSlot> slots //후보 선정 조건을 통과한 슬롯 목록
) {
}
