package com.example.attractionreservation.domain.routeRecommendation.dto;

/**
 * 경로 추천에 고를 수 있는 어트랙션 한 건.
 * 운영 중이고 좌표(attraction_locations)가 등록된 어트랙션만 이 목록에 들어온다. (좌표가 있어야 첫 구간을 계산할 수 있다)
 * 고른 어트랙션끼리의 구간 데이터는 조합마다 달라 보장하지 않는다. 없으면 추천 요청이 ROUTE_NO_VALID_ORDER(409)로 실패한다.
 * 사진·운영 상태 등 화면 정보는 어트랙션 목록 API에서 가져오고, 이 목록은 "고를 수 있는지"와 "지도 위 위치"를 알려준다.
 *
 * latitude, longitude: 화면이 지도에 마커를 찍을 때 쓰는 어트랙션 좌표 (WGS84).
 *   이 목록에 들어온 어트랙션은 좌표가 반드시 있으므로 null 이 아니다. (inner join)
 */
public record RecommendableAttractionResponse(
        Long attractionId,
        String attractionName,
        Double latitude,
        Double longitude
) {
}