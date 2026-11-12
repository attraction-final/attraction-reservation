# 위치는 추천 전에 거르고, 오래된 위치는 경고만 붙이기

> 분류: 데이터 정합성 · 기준 코드: `dev` `RouteRecommendationService` (`validateAccuracy`, `validateServiceArea`, `toLocation`), `RouteRecommendationProperties`

| 항목 | 내용 |
| --- | --- |
| 문제 | 오차가 큰 위치나 놀이공원 밖 위치로 추천하면 첫 구간이 크게 틀리고, 그 뒤 슬롯 선택까지 모두 틀어진다 |
| 결정 | 오차 100m 이상, 서비스 지역(2,000m) 밖은 거부한다. 5분 넘은 위치는 막지 않고 경고를 붙인다 |
| 근거 | 틀린 위치로 탐색까지 돌리지 않는다. 오래된 위치를 막으면 실내에서는 추천을 아예 못 쓴다 |
| 한계 | 서비스 지역은 원형 경계라 정밀하지 않다. 실제 경계는 "2,000.5m 미만"이다 |

## 1. 문제

추천의 출발점은 브라우저 Geolocation이 준 좌표다. 오차가 큰 위치나 놀이공원 밖 위치로 추천하면 [첫 구간 추정](route-start-leg-haversine.md)이 크게 틀리고, 그 뒤 슬롯 선택까지 모두 틀어진다.

## 2. 결정

| 상황 | 처리 | 설정 (`route-recommendation.*`) |
| --- | --- | --- |
| 위치 오차(`accuracy`)가 100m 이상 | 400으로 거부 | `accuracy-limit-meters: 100` |
| 가장 가까운 선택 어트랙션까지 직선거리가 2,000m 초과 | 400으로 거부 (`LOCATION_OUT_OF_SERVICE_AREA`) | `max-start-distance-meters: 2000` |
| 측정 시각이 5분 넘게 지남 | 거부하지 않고 응답에 `STALE_POSITION` 경고 | `stale-after: 5m` |

## 3. 근거

- 오차는 DB를 읽기 전에, 서비스 지역은 슬롯 조회와 경로 탐색 전에 판단한다. 틀린 위치로 탐색까지 돌리지 않는다.
- 서비스 지역은 경계 데이터(Geofence) 없이 "가장 가까운 선택 어트랙션 기준 원"으로 판정한다. 정밀하지 않지만 추가 데이터가 필요 없다.
- 오래된 위치는 사용자가 실내에 있거나 GPS가 늦게 갱신된 경우가 많다. 막아 버리면 추천 자체를 못 쓰므로, 경고를 붙여 화면이 다시 측정할지 판단하게 했다.
- 요청 위치는 응답에만 쓰고 저장하지 않는다.

## 4. 한계

- 판정에 쓰는 직선거리는 반올림한 값이라 실제 경계는 "2,000.5m 미만"이다. → [첫 구간 추정의 올림·부동소수점 점검](../troubleshooting/route-start-leg-estimation.md) 3-3

## 관련 문서

- [하버사인 + 실측 기반 보정](route-start-leg-haversine.md)
- [경로 추천 API](../api/route-recommendations.md)
- [최적 경로 추천 기술 결정 목록](route-recommendation.md)
