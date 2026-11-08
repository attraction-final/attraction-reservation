# 관리자 예약 목록 실행 계획 분석 및 조회 최적화

> **KAN-133** · 개선 전 측정 완료. 전체 목록의 스캔·정렬과 RESERVED count를 우선 검토한다. 쿼리·인덱스 변경 및 개선 후 측정은 아직 진행하지 않았다.

## 1. 목적과 측정 조건

KAN-129의 fetch join과 KAN-130의 회원용 인덱스가 반영된 코드에서 관리자 목록·count 비용을 측정했다. BR-RSV-20의 필터·검색·페이징 계약을 유지하는 범위이며, 허용 시간과 데이터 규모를 새 비즈니스 정책으로 정하지 않았다.

| 항목 | 조건 |
| --- | --- |
| DB·스키마 | Testcontainers MySQL 8.4.11, Flyway V1~V4, Hibernate validate |
| 격리 수준 | REPEATABLE-READ |
| 데이터 | 회원 200명, 예약 100,000건, 슬롯 500개, 시설 10개 |
| 분포 | 회원별 500건, 네 상태 각각 25% |
| 날짜·슬롯 | 2026-10-01부터 10일, 하루 50개·시설별 5개 슬롯 |
| 페이지·정렬 | 첫 페이지 10건, status DESC, id DESC |
| 반복 | 쿼리별 EXPLAIN ANALYZE 워밍업 1회 후 5회 |
| 실행 범위 | 실제 Service·Repository·DTO, 사용하지 않는 협력 Service만 mock |
| 초기화 | fixture 커밋 후 ANALYZE TABLE, 2차·Query Cache 비활성화 |

실제 PreparedStatement의 SQL과 JDBC 바인딩을 그대로 재사용했다. 응답 검증용 기준 SQL은 계획 분석용 SQL과 구분했다. 합성 데이터이며 운영 트래픽·이력 분포로 가정하지 않는다.

## 2. 측정 결과

실행 시작은 **2026-11-08 22:47:57 KST**, 테스트 **1개 통과**, 실패·오류·건너뜀 0개다. 세 Service 호출 모두 SELECT 2개였고 응답·정렬·전체 건수·페이지 검증이 통과했다.

| 조건 | 대상 건수 | 목록 중앙값(ms) | count 중앙값(ms) |
| --- | ---: | ---: | ---: |
| 전체 | 100,000 | **65.1** | 23.8 |
| RESERVED | 25,000 | 0.063 | **35.4** |
| 어트랙션 3 + 2026-10-04 | 1,000 | 2.72 | 0.273 |

시간은 최상위 노드 actual time의 완료 값이며 아래 5회 값의 중앙값이다.

| 쿼리 | 5회 완료 시간(ms) |
| --- | --- |
| 전체 목록 | 65.1, 65.1, 66.0, 65.8, 65.1 |
| 전체 count | 23.7, 23.8, 23.6, 23.8, 23.8 |
| RESERVED 목록 | 0.0621, 0.0948, 0.1030, 0.0613, 0.0630 |
| RESERVED count | 33.4, 35.6, 35.4, 34.0, 36.9 |
| 어트랙션·날짜 목록 | 2.66, 2.74, 2.69, 2.83, 2.72 |
| 어트랙션·날짜 count | 0.292, 0.263, 0.299, 0.273, 0.251 |

## 3. 실행 계획의 핵심

| 쿼리 | 실제 읽기·처리 경로 | 판단 |
| --- | --- | --- |
| 전체 목록 | 예약 100,000건 table scan → 상태·ID Sort → 슬롯·시설 PK 각 10회 → 10건 반환 | **1순위**: 입력량과 정렬 비용 |
| 전체 count | 슬롯 500개 covering scan → 슬롯별 예약 200건 × 500회 → 100,000건 집계 | 정확한 전체 count 비용을 별도 검토 |
| RESERVED 목록 | PK 역방향 40건 → 상태 10건 필터 → 슬롯·시설 PK 각 10회 | 이번 분포에서는 조기 종료 |
| RESERVED count | 예약 100,000건 scan → 25,000건 상태 필터 → 슬롯 PK 25,000회 | **2순위**: 상태 검색·count join 비용 |
| 시설·날짜 목록 | 슬롯 복합 인덱스로 5개 → 예약 200건 × 5회 → 1,000건 입력 정렬 | 기존 범위 인덱스 활용 |
| 시설·날짜 count | 슬롯 5개와 예약 1,000건을 covering 경로로 집계 | 초기 우선순위는 낮음 |

대표 계획을 간추리면 다음과 같다.

```text
전체 목록
Table scan reservations: rows=100000 loops=1
→ Sort: reservation_status DESC, id DESC
→ Limit 10

RESERVED count
Table scan reservations: rows=100000 loops=1
→ Filter RESERVED: rows=25000 loops=1
→ 슬롯 PK lookup: rows=1 loops=25000
→ Aggregate count

어트랙션·날짜
idx_attraction_slots_attraction_starts: rows=5 loops=1
→ 예약 FK lookup: rows=200 loops=5
```

`rows`는 `loops`와 함께 해석한다. count SQL에는 시설 join도 있지만 전체·상태 count의 실제 계획에는 시설 접근 노드가 나타나지 않았다. SQL에 선언된 join을 모두 실제 반복 접근으로 계산하지 않는다.

## 4. 다음 개선과 검증 기준

| 순서 | 할 일 | 유지·확인할 내용 |
| --- | --- | --- |
| 1 | 전체 목록의 스캔·Sort를 줄이는 후보 실험 | 실제 사용 인덱스·입력 행 수·다른 조회 영향 |
| 2 | RESERVED count의 상태 검색·join 검토 | FK·NULL·필터와 결과 동등성 |
| 3 | 동일 데이터·조건으로 재측정 | 목록·count를 각각 워밍업 후 5회 비교 |
| 4 | 회귀 검증 | 응답·필터·정렬·페이지·전체 건수 유지 |
| 5 | 인덱스 추가 시 비용 확인 | 저장 공간·INSERT·상태 UPDATE 유지 비용 |

현재 변경 후보와 migration 번호는 미정이다. 효과를 분리하기 위해 한 변경씩 적용한다. 스키마 변경은 엔티티와 새 Flyway migration에 함께 반영하고 기존 적용 파일은 수정하지 않는다.

## 5. 해석의 한계

- 이 테스트는 응답과 수집 성공을 검증한다. 느리거나 Sort가 있다는 이유로 실패하지 않으므로 통과가 최적화 완료를 뜻하지 않는다.
- 정확한 전체 count와 LIMIT 10 목록은 처리 요구량이 다르다. count도 10건만 읽는 것을 목표로 잡지 않는다.
- RESERVED 목록의 빠른 종료는 현재 상태 분포에서의 결과다. 희소하거나 ID 구간에 편중된 상태는 별도 확인한다.
- Service·검증 조회·EXPLAIN ANALYZE가 캐시에 영향을 주는 warm-cache 결과다.
- 내부 노드 시간을 합산하거나 목록·count 시간을 더해 HTTP 응답 시간으로 표현하지 않는다.
- 날짜 필터의 모든 경계값, 깊은 offset, 모든 상태·조건 조합, 운영 p95·최대 처리량은 검증하지 않았다.

## 6. 재현과 현재 상태

```bash
./gradlew test --tests "*AdminReservationListPlanIntegrationTest" --rerun-tasks
```

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListPlanIntegrationTest.java)
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java)
- [Service](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java)
- [응답 DTO](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationResponse.java)
- [Flyway 규칙](../database/migration.md)
- [MySQL 실행 계획 해석](https://dev.mysql.com/doc/refman/8.4/en/explain.html)

| 항목 | 상태 |
| --- | --- |
| 개선 전 측정·핵심 기록 | 완료 |
| 후보 선택·구현 | 미진행 |
| 개선 후 재측정·기존 테스트 회귀 | 미진행 |
| 저장 공간·쓰기 비용·PR CI | 해당 변경 후 확인 |

수치는 당시 사용자가 실행한 실제 결과에서 확인했다. 원본 로그·XML·해시 첨부 대신 핵심 지표와 대표 계획을 본문에 남겼으며, 문서 정리 중 테스트를 재실행하지 않았다.
