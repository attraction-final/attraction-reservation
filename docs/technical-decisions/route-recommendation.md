# 최적 경로 추천 기술 결정 목록

> 기준 코드: `dev` (KAN-163 ~ KAN-167 반영 후) · API: [route-recommendations.md](../api/route-recommendations.md)

고른 어트랙션(최대 5개)을 슬롯 시간 안에서 가장 많이, 가장 빨리 도는 순서를 찾고, 그 결과를 한 번에 예약한다. 주제마다 문서를 나눴다.

| 분류 | 주제 | 한 줄 요약 | 문서 |
| --- | --- | --- | --- |
| 기술적 도전 | DFS + 백트래킹 완전 탐색 | 최대 326개 노드라 전부 봐도 빠르다. 그리디가 1곳만 찾는 경우에 2곳을 찾는다 | [route-search-dfs-backtracking.md](route-search-dfs-backtracking.md) |
| 기술적 도전 | 도착 후 가장 이른 슬롯 고정 | 탐색 공간을 `순열 × 40⁵`에서 `순열`로 줄인다 | [route-slot-earliest-after-arrival.md](route-slot-earliest-after-arrival.md) |
| 기술적 도전 | 하버사인 + 실측 기반 첫 구간 추정 | 외부 API 없이 좌표만으로 추정한다. 보정값 1.68 · 52.8은 실측 90건의 중앙값 | [route-start-leg-haversine.md](route-start-leg-haversine.md) |
| 트러블슈팅 | 첫 구간 올림의 부동소수점 점검 | 기본값에서는 0건, 설정값을 바꾸면 1분 길게 나올 수 있음을 전수 계산으로 확인 | [route-start-leg-estimation.md](../troubleshooting/route-start-leg-estimation.md) |
| 데이터 정합성 | 방향별 실측 구간만 사용 | 없는 구간을 역방향 값이나 추정값으로 채우지 않고 그 순서를 버린다 | [route-directional-measured-paths.md](route-directional-measured-paths.md) |
| 데이터 정합성 | 위치 검증과 서비스 지역 | 오차 100m 이상 · 2,000m 밖은 거부, 5분 넘은 위치는 경고 | [route-location-validation.md](route-location-validation.md) |
| 데이터 정합성 · 동시성 | 일괄 예약 all-or-nothing + ID 순서 잠금 | 하나라도 실패하면 모두 롤백, 엇갈린 순서의 동시 일괄 예약도 교착 없음 | [route-bulk-reservation-lock-order.md](route-bulk-reservation-lock-order.md) |
| 성능 · 조회 최적화 | 구간 쿼리 1번 + 메모리 조회표 | 탐색 중 DB 조회 0회, 알고리즘을 순수 단위 테스트로 검증 | [route-travel-time-single-query.md](route-travel-time-single-query.md) |
