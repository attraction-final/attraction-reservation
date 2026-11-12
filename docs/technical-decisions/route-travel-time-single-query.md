# 탐색 중 DB 조회 0회: 구간을 쿼리 1번으로 읽어 메모리 조회표로 탐색하기

> 분류: 성능 · 조회 최적화 · 관련 이슈: KAN-164 · 기준 코드: `dev` `AttractionPathRepository#findAllBetween`, `TravelTimeTable`, `PathEdge`

| 항목 | 내용 |
| --- | --- |
| 문제 | 탐색은 최대 326개 노드를 지나며 매번 "A → B 이동 시간"을 묻는다. 그때마다 DB를 조회하면 요청 하나에 수백 번의 쿼리가 나간다 |
| 결정 | 선택한 어트랙션끼리의 구간을 쿼리 1번(최대 20행)으로 읽어 메모리 조회표로 만든다 |
| 효과 | DB 조회 횟수가 탐색량과 관계없이 고정된다. 알고리즘을 Spring·DB 없이 단위 테스트할 수 있다 |

## 1. 문제

탐색은 최대 326개 노드를 지나며 매번 "A → B 이동 시간"을 묻는다. 그때마다 DB를 조회하면 요청 하나에 수백 번의 쿼리가 나간다 (N+1).

## 2. 결정

- 선택한 어트랙션끼리의 구간을 **쿼리 1번**으로 모두 읽는다 (`findAllBetween`, 5개면 최대 `5 × 4 = 20`행, `uk_attraction_paths_from_to` 인덱스 사용).
- 활성 구간만 `출발 ID → (도착 ID → 구간)` 형태의 `TravelTimeTable`로 만들고, 탐색 중에는 메모리에서만 `O(1)`로 찾는다.
- 알고리즘은 JPA 엔티티(`AttractionPath`) 대신 `PathEdge` record만 받는다.

## 3. 근거

- DB 조회 횟수가 탐색량과 관계없이 **고정**된다.
- 알고리즘 클래스(`RouteSearcher`, `TravelTimeTable`)가 Spring·DB 없이 돌아가므로 **순수 단위 테스트**로 그리디 반례, 방향별 구간, 경계 시각 같은 케이스를 빠르게 검증할 수 있다.
- 탐색 중 상태(현재 경로, 방문 집합, 최선 결과)는 메서드 인자로만 넘겨 싱글톤 빈 필드에 두지 않는다. 동시 요청이 와도 서로 섞이지 않는다.
- `AttractionPath`는 `@Immutable`이라 추천 로직에서 실수로 값을 바꿔도 UPDATE가 나가지 않는다.

## 4. 한계

- 쿼리 수 감소를 부하 테스트로 측정하지는 않았다. 선택 개수가 최대 5개라 읽는 행 수의 상한(20행)으로 판단했다.

## 관련 문서

- [방향별 실측 구간만 사용](route-directional-measured-paths.md)
- [DFS + 백트래킹 완전 탐색](route-search-dfs-backtracking.md)
- [최적 경로 추천 기술 결정 목록](route-recommendation.md)
