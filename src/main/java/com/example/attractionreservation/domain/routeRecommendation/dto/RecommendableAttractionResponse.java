package com.example.attractionreservation.domain.routeRecommendation.dto;

/**
 * 경로 추천에 고를 수 있는 어트랙션 한 건.
 * 좌표(attraction_locations)가 등록된 어트랙션만 첫 구간을 계산할 수 있어 이 목록에 들어온다.
 * 사진·운영 상태 등 화면 정보는 어트랙션 목록 API에서 가져오고, 이 목록은 "고를 수 있는지"만 알려준다.
 */
public record RecommendableAttractionResponse(
        Long attractionId,
        String attractionName
) {
}
