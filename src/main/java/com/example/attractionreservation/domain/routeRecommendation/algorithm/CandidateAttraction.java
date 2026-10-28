package com.example.attractionreservation.domain.routeRecommendation.algorithm;

import java.util.List;
//후보 선정을 통과한 어트랙션 한 건과 사용 가능한 후보 슬롯 목록
public record CandidateAttraction(
        Long attractionId,
        String attractionName,
        int moveMinutes, //이 어트랙션으로 이동할 때 적용할 예상 이동 시간
        List<CandidateSlot> slots //후보 선정 조건을 통과한 슬롯 목록
) {
}
