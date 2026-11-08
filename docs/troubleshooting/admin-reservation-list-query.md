# 관리자 예약 목록 실행 계획 분석 및 조회 최적화

> **KAN-133** · 1차로 상태·ID 인덱스를 적용하여 전체 목록의 스캔·Sort를 제거했다. RESERVED 목록은 이번 측정에서 시간이 늘었고 count 비용도 남아 있어 후속 검토가 필요하다.

## 1. 목적과 측정 조건

KAN-129의 fetch join과 KAN-130의 회원용 인덱스가 반영된 코드에서 관리자 목록·count 비용을 측정했다. BR-RSV-20의 필터·검색·페이징 계약을 유지하는 범위이며, 허용 시간과 데이터 규모를 새 비즈니스 정책으로 정하지 않았다.

| 항목 | 조건 |
| --- | --- |
| DB·스키마 | Testcontainers MySQL 8.4.11, 개선 전 Flyway V1~V4 / 1차 변경 후 V1~V5, Hibernate validate |
| 격리 수준 | REPEATABLE-READ |
| 데이터 | 회원 200명, 예약 100,000건, 슬롯 500개, 시설 10개 |
| 분포 | 회원별 500건, 네 상태 각각 25% |
| 날짜·슬롯 | 2026-10-01부터 10일, 하루 50개·시설별 5개 슬롯 |
| 페이지·정렬 | 첫 페이지 10건, status DESC, id DESC |
| 반복 | 쿼리별 EXPLAIN ANALYZE 워밍업 1회 후 5회 |
| 실행 범위 | 실제 Service·Repository·DTO, 사용하지 않는 협력 Service만 mock |
| 초기화 | fixture 커밋 후 ANALYZE TABLE, 2차·Query Cache 비활성화 |

실제 PreparedStatement의 SQL과 JDBC 바인딩을 그대로 재사용했다. 응답 검증용 기준 SQL은 계획 분석용 SQL과 구분했다. 합성 데이터이며 운영 트래픽·이력 분포로 가정하지 않는다.

## 2. 개선 전 측정 결과

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

## 3. 개선 전 실행 계획의 핵심

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

## 4. 1차 변경: 상태·ID 복합 인덱스

회원 조건 없는 기본 정렬을 지원하는 후보로 다음 일반 복합 인덱스를 추가했다.

```sql
CREATE INDEX idx_reservations_status_id
    ON reservations (reservation_status, id);
```

Reservation의 `@Table.indexes`와 [Flyway V5](../../src/main/resources/db/migration/V5__add_reservation_status_id_index.sql)에 같은 이름·컬럼 순서를 정의했다. 기존 회원용 인덱스와 활성 예약 UNIQUE 제약은 유지했으며, Repository의 목록·count 쿼리는 수정하지 않았다.

V5 적용 후 재측정 시작은 **2026-11-08 23:21:59 KST**다. 실제 XML에서 테스트 **1개 통과**, 실패·오류·건너뜀 0개를 확인했다. Flyway 이력의 V5 `success=true`와 실제 계획의 새 인덱스 사용도 확인했다.

### 실행 계획 변화

| 쿼리 | V5 적용 후 계획 | 해석 |
| --- | --- | --- |
| 전체 목록 | 새 인덱스 역방향 scan, 예약 `rows=10 loops=1`, Sort 없음 | 100,000건 스캔·정렬을 제거 |
| 전체 count | 슬롯 500개 → 슬롯별 예약 200건 × 500회 | 주요 처리 구조 유지 |
| RESERVED 목록 | 새 인덱스에서 상태 조건 역방향 lookup, 예약 `rows=10 loops=1` | PK 40건 읽기에서 10건으로 줄었지만 시간은 증가 |
| RESERVED count | 새 인덱스로 예약 25,000건 조회 → 슬롯 PK 25,000회 | 입력 범위는 줄었으나 join·집계 비용 유지 |
| 시설·날짜 목록·count | 기존 슬롯 복합 인덱스와 예약 FK 인덱스 사용 | 주요 구조 유지, 목록 Sort도 유지 |

대표 개선 경로:

```text
전체 목록
이전: 예약 table scan rows=100000 → Sort → Limit 10
현재: idx_reservations_status_id 역방향 scan rows=10 → Limit 10

RESERVED count
현재: 상태 인덱스 lookup rows=25000 loops=1
    → 슬롯 PK lookup rows=1 loops=25000
    → Aggregate count
```

목록의 슬롯·시설 PK 조회는 각각 10회다. 조회 행 수가 줄었다는 이유만으로 모든 쿼리의 시간이 감소했다고 판단하지 않는다.

### 완료 시간의 중앙값 비교

| 쿼리 | 개선 전(ms) | V5 적용 후(ms) | 관찰 |
| --- | ---: | ---: | --- |
| 전체 목록 | 65.1 | **0.0605** | 스캔·Sort 제거와 함께 큰 감소 |
| 전체 count | 23.8 | 24.1 | 뚜렷한 감소 없음 |
| RESERVED 목록 | 0.063 | **1.07** | 이번 실행에서는 증가 |
| RESERVED count | 35.4 | 36.2 | 뚜렷한 감소 없음 |
| 시설·날짜 목록 | 2.72 | 2.87 | 주요 계획 유지 |
| 시설·날짜 count | 0.273 | 0.251 | 주요 계획 유지 |

V5 적용 후 5회 원측정값은 다음과 같다. 개선 전 값은 2절에 기록했다.

| 쿼리 | 5회 완료 시간(ms) |
| --- | --- |
| 전체 목록 | 0.0610, 0.0605, 0.0601, 0.1040, 0.0574 |
| 전체 count | 23.5, 24.1, 25.6, 24.3, 24.1 |
| RESERVED 목록 | 1.05, 1.09, 1.15, 1.05, 1.07 |
| RESERVED count | 35.5, 35.9, 37.7, 36.2, 37.3 |
| 시설·날짜 목록 | 2.71, 2.63, 3.57, 3.06, 2.87 |
| 시설·날짜 count | 0.258, 0.250, 0.257, 0.250, 0.251 |

**현재 판단:** 전체 목록의 개선 효과는 확인했지만 모든 관리자 조회가 개선됐다고 결론 내리지 않는다. RESERVED 목록은 읽은 예약 행 수가 40→10으로 줄어도 시간이 늘었다. 이 결과만으로 증가 원인이나 운영 영향을 단정하지 않으며, 후속 변경에서도 같은 조건을 다시 비교한다.

정확한 count의 행 집계와 슬롯 접근이 남아 있다. 다음은 count join이 필터·FK·NULL 조건상 언제 필요한지 검토하고, 결과 동등성을 유지하면서 바꿀 수 있는 범위를 확인한다. V5의 최종 채택은 다른 조건의 변화와 저장 공간·쓰기 비용·회귀 결과까지 보고 판단한다.

## 5. 다음 개선과 검증 기준

| 순서 | 할 일 | 유지·확인할 내용 |
| --- | --- | --- |
| 1 | V5로 전체 목록 개선 확인 | 완료. RESERVED 목록 시간 증가는 후속 비교에서 계속 확인 |
| 2 | RESERVED count의 상태 검색·join 검토 | FK·NULL·필터와 결과 동등성 |
| 3 | 동일 데이터·조건으로 재측정 | 목록·count를 각각 워밍업 후 5회 비교 |
| 4 | 회귀 검증 | 응답·필터·정렬·페이지·전체 건수 유지 |
| 5 | 인덱스 추가 시 비용 확인 | 저장 공간·INSERT·상태 UPDATE 유지 비용 |

1차 후보는 V5에 반영했다. 다음 count 쿼리 변경은 아직 하지 않았다. 효과를 분리하기 위해 한 변경씩 적용한다. 추가 스키마 변경이 필요하면 엔티티와 새 Flyway migration에 함께 반영하고 기존 적용 파일은 수정하지 않는다.

## 6. 해석의 한계

- 이 테스트는 응답과 수집 성공을 검증한다. 느리거나 Sort가 있다는 이유로 실패하지 않으므로 통과가 최적화 완료를 뜻하지 않는다.
- 정확한 전체 count와 LIMIT 10 목록은 처리 요구량이 다르다. count도 10건만 읽는 것을 목표로 잡지 않는다.
- RESERVED 목록의 조회 행 수와 시간은 현재 상태 분포에서의 결과다. 희소하거나 ID 구간에 편중된 상태는 별도 확인한다.
- Service·검증 조회·EXPLAIN ANALYZE가 캐시에 영향을 주는 warm-cache 결과다.
- 내부 노드 시간을 합산하거나 목록·count 시간을 더해 HTTP 응답 시간으로 표현하지 않는다.
- 날짜 필터의 모든 경계값, 깊은 offset, 모든 상태·조건 조합, 운영 p95·최대 처리량은 검증하지 않았다.

## 7. 재현과 현재 상태

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
| 1차 인덱스 후보·V5 적용 | 완료 |
| V5 후 동일 조건 재측정 | 완료. 전체 목록 개선, RESERVED 목록 시간 증가 관찰 |
| count join 검토·추가 변경 | 미진행 |
| 관련 기존 테스트 전체 회귀 | 미진행 |
| V5 저장 공간·쓰기 비용·PR CI | 미진행 |

수치는 당시 사용자가 실행한 실제 결과에서 확인했다. 원본 로그·XML·해시 첨부 대신 핵심 지표와 대표 계획을 본문에 남겼으며, 문서 정리 중 테스트를 재실행하지 않았다.
