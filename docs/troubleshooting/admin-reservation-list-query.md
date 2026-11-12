# 관리자 예약 목록 실행 계획 분석 및 조회 최적화

> **KAN-133** · 상태·ID 인덱스와 조건부 count join의 조회 효과, V5의 공간·쓰기 비용을 확인했다. 관련 예약·Flyway 회귀는 **43개 Suite·261개 모두 통과**했다. RESERVED 목록 시간 증가의 해석과 PR 검증은 남아 있다.

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

이 단계에서는 정확한 count의 행 집계와 슬롯 접근이 남았다. 이어서 아래 2차 변경으로 count join을 조건부로 구성했다. V5의 최종 채택은 다른 조건의 변화와 저장 공간·쓰기 비용·회귀 결과까지 보고 판단한다.

## 5. 2차 변경: 검색 조건에 따른 count join

`findAdminReservationByCondition()`의 count 구성을 `JPAQuery<Long>` 변수로 분리하고, 필요한 경우에만 슬롯·시설 join을 추가했다. content 쿼리와 기존 검색 조건·전체 건수 계약은 유지했다.

| 검색 조건 | count 구성 | 이유 |
| --- | --- | --- |
| 필터 없음 또는 회원·상태만 지정 | 예약 테이블만 집계 | 필요한 회원 ID·상태가 예약에 있음 |
| 시설 ID·시작일·다음 날 경계 중 하나라도 지정 | 기존 슬롯·시설 join 유지 | 시설·슬롯 날짜 조건을 적용해야 함 |

```text
기본 count 쿼리 생성
→ attractionId != null || startDay != null || nextDay != null이면 두 join 추가
→ where 조건은 분기 밖에서 항상 적용
→ 마지막에 fetchOne() 실행
```

현 스키마에서 예약→슬롯과 슬롯→시설은 NOT NULL FK 관계이고, 기존 count에 별도의 삭제 상태 필터는 없다. 이 전제에서 시설·날짜 조건이 없는 count의 join을 생략했다. 연관관계·공통 필터가 바뀌면 결과 동등성을 다시 확인해야 한다.

### 재측정 결과

실행 시작은 **2026-11-09 00:44:31 KST**다. 테스트 XML에서 **1개 통과**, 실패·오류·건너뜀 0개를 확인했다. 전체 100,000건, RESERVED 25,000건, 시설·날짜 1,000건과 응답·정렬·페이지 정보가 유지됐다.

RESERVED count의 실제 SQL은 다음과 같이 바뀌었다.

```sql
SELECT COUNT(r.id)
FROM reservations r
WHERE r.reservation_status = ?;
```

바인딩은 `RESERVED`다. 새 상태·ID 인덱스의 covering lookup으로 25,000건을 집계하며, 이전의 슬롯 PK 조회 25,000회가 없어졌다.

| count 쿼리 | V5만 적용한 중앙값(ms) | 조건부 join 후 중앙값(ms) | 계획 변화 |
| --- | ---: | ---: | --- |
| 전체 | 24.1 | **11.0** | 슬롯 join 없이 예약 count |
| RESERVED | 36.2 | **6.89** | 상태 covering index lookup, 슬롯 접근 제거 |
| 시설·날짜 | 0.251 | 0.250 | 기존 join·범위 인덱스 경로 유지 |

| count 쿼리 | 조건부 join 후 5회 완료 시간(ms) |
| --- | --- |
| 전체 | 11.7, 10.7, 11.2, 11.0, 10.6 |
| RESERVED | 6.94, 6.77, 7.02, 6.89, 6.86 |
| 시설·날짜 | 0.246, 0.248, 0.250, 0.284, 0.250 |

목록은 변경하지 않았으며, 같은 재실행의 목록 중앙값은 전체 0.0598ms, RESERVED 1.14ms, 시설·날짜 2.93ms였다. 이전 대비 시간 차이를 count 변경으로 인한 목록 개선·악화로 단정하지 않는다. RESERVED 목록의 V5 이전 대비 시간 증가는 여전히 남은 관찰 항목이다.

성능 측정은 조건 없는 분기와 시설·날짜 분기를 확인했다. 이후 별도 회귀 테스트에서 회원·시설·날짜의 8개 조합과 join 없는 빈 결과까지 보강했다. 다음 절에 결과를 구분해 기록한다.

### 결과 파일을 읽는 방법

테스트 실행 후 `build/reports/admin-reservation-list-query/run-.../`의 최신 실행 폴더를 확인한다. 예를 들어 `reserved-count-analyze-1.txt`부터 `-5.txt`가 RESERVED count의 반복 계획이다. Git에는 핵심 수치와 대표 계획을 이 문서에만 남긴다.

| 표시 | 해석 |
| --- | --- |
| 최상위 actual time의 완료 값 | 해당 실행 완료 시간(ms) |
| cost | 옵티마이저 추정 비용. 실제 ms가 아님 |
| rows, loops | 노드별 반환 행 수와 반복 횟수 |
| Sort, Nested loop | 정렬·join 작업 존재 여부 |
| Covering index lookup | 필요한 값을 인덱스만으로 읽는 경로 |

count 최상위의 `rows=1`은 집계 결과 한 행을 반환한다는 뜻이다. 원본 데이터 한 행만 읽었다는 의미가 아니다. 전체 count의 간단한 `Count rows` 출력도 O(1) 집계의 증거로 해석하지 않는다.

## 6. 조건별 회귀와 V5 유지 비용

### 조건별 회귀 20개

2026-11-09 **01:00:24 KST**의 AdminReservationListQueryIntegrationTest XML에서 **20개 통과**, 실패·오류·건너뜀 0개를 확인했다.

| 범위 | 케이스 수 | 확인 내용 |
| --- | ---: | --- |
| 기존 관리자 목록 회귀 | 11 | 응답·회원·시설·상태·날짜·복합 조건·빈 결과·페이지 |
| 회원·시설·날짜 조건 조합 | 8 | 전체 건수·예약 ID 유지, 실제 count SQL의 연관 테이블 접근 필요 여부 |
| join 없는 빈 결과 | 1 | 없는 회원 검색에서 전체 건수 0, 빈 페이지, count join 생략 |
| **합계** | **20** | 실패·오류·건너뜀 0개 |

SQL은 테스트 전용 StatementInspector로 수집하며 결과나 SQL을 변경하지 않는다. 이 클래스는 엔티티로 스키마를 만드는 create-drop 테스트이므로 Flyway·운영 DB의 인덱스 검증을 대체하지 않는다.

### 유지 비용의 비교 조건

2026-11-09 **01:08:50 KST**의 관리자 계획·유지 비용 측정 테스트는 1개 통과했다. 측정 도우미가 이슈·인덱스를 지정받도록 보완하고 V5인 `idx_reservations_status_id`를 선택했다.

| 비교 항목 | 구성 |
| --- | --- |
| 기준 테이블 | 기존 인덱스들 + V4 회원용 인덱스 |
| 추가 테이블 | 동일 구성 + V5 상태·ID 인덱스 |
| 초기 데이터 | 각각 예약 100,000건, 기존 UNIQUE·Generated Column·FK 유지 |
| 반복 | 워밍업 1회 후 5회, 인덱스 유무의 실행 순서 교대 |
| INSERT | RESERVED 예약 1,000건, 동일 ID·값 사용 |
| UPDATE | 기존 RESERVED 1,000건을 CANCELLED로 변경, 사유·시각·updated_at 포함 |
| 시간 범위 | JDBC batch와 트랜잭션 시작·커밋, 준비·검증·DDL·통계 갱신은 제외 |
| 최종 검증 | 양쪽 106,000건·상태 분포 일치, 원본 예약 100,000건 유지 |

V5만 유무를 다르게 하므로 V4 인덱스 비용을 함께 제거한 비교가 아니다. 모든 DDL·DML은 테스트 전용 컨테이너 안에서 수행한다.

### 저장 공간과 쓰기 결과

예약 100,000건에서 새 인덱스는 **161 pages × 16,384 bytes = 2,637,824 bytes, 약 2.52MiB**로 추정됐다. mysql.innodb_index_stats의 size 통계에 페이지 크기를 곱한 값이며 정확한 파일 실사용량은 아니다.

아래 값은 **1,000건 + 커밋의 ms**이고, 5회 측정값은 크기순으로 정리했다.

| 작업·조건 | 5회 측정값(ms) | 중앙값(ms) |
| --- | --- | ---: |
| INSERT, V5 없음 | 47.590801, 52.848400, 63.867400, 66.563601, 83.189000 | 63.8674 |
| INSERT, V5 있음 | 47.643501, 47.690899, 49.291900, 52.842200, 60.086200 | 49.2919 |
| UPDATE, V5 없음 | 169.832201, 171.168900, 177.810200, 208.036899, 295.004901 | 177.8102 |
| UPDATE, V5 있음 | 185.934101, 190.084200, 201.085800, 239.015100, 247.654800 | 201.0858 |

상태 UPDATE 중앙값은 +23.2756ms였다. INSERT는 V5가 있는 쪽이 더 짧게 나왔지만, 인덱스가 INSERT를 빠르게 한다는 근거로 사용하지 않는다. 캐시·I/O·페이지 배치의 영향을 배제하지 못한 단일 스레드 합성 실험이다.

이 측정은 업무 Service·슬롯 인원 변경·HTTP·동시 잠금 경합을 포함하지 않는다. 인덱스의 최종 채택은 조회 이득·추가 공간·쓰기 결과와 회귀 검증을 함께 보고 판단하며, 운영 지연·처리량의 보장으로 표현하지 않는다.

## 7. 다음 개선과 검증 기준

| 순서 | 할 일 | 유지·확인할 내용 |
| --- | --- | --- |
| 1 | V5로 전체 목록 개선 확인 | 완료. RESERVED 목록 시간 증가는 후속 비교에서 계속 확인 |
| 2 | count의 조건부 join 적용 | 완료. 세 측정 조건의 건수 유지와 join 제거 확인 |
| 3 | 동일 데이터·조건으로 재측정 | 완료. 각 변경 단계의 중앙값을 분리해 기록 |
| 4 | 회귀 검증 | 조건별 20개 포함 관련 예약·Flyway 43개 Suite·261개 통과 |
| 5 | 인덱스 추가 시 비용 확인 | 완료. 약 2.52MiB와 합성 쓰기 비용 기록, 운영 영향은 별도 검증 |

1차 인덱스와 2차 count 쿼리 변경을 분리해 측정했다. 추가 스키마 변경이 필요하면 엔티티와 새 Flyway migration에 함께 반영하고 기존 적용 파일은 수정하지 않는다.

## 8. 해석의 한계

- 이 테스트는 응답과 수집 성공을 검증한다. 느리거나 Sort가 있다는 이유로 실패하지 않으므로 통과가 최적화 완료를 뜻하지 않는다.
- 정확한 전체 count와 LIMIT 10 목록은 처리 요구량이 다르다. count도 10건만 읽는 것을 목표로 잡지 않는다.
- RESERVED 목록의 조회 행 수와 시간은 현재 상태 분포에서의 결과다. 희소하거나 ID 구간에 편중된 상태는 별도 확인한다.
- Service·검증 조회·EXPLAIN ANALYZE가 캐시에 영향을 주는 warm-cache 결과다.
- 내부 노드 시간을 합산하거나 목록·count 시간을 더해 HTTP 응답 시간으로 표현하지 않는다.
- 날짜 필터의 모든 경계값, 깊은 offset, 모든 상태·조건 조합, 운영 p95·최대 처리량은 검증하지 않았다.

## 9. 재현과 현재 상태

```bash
./gradlew test --tests "*AdminReservationListPlanIntegrationTest" --rerun-tasks
```

조건별 회귀와 관련 기존 테스트 실행 명령:

```bash
./gradlew test --tests "*AdminReservationListQueryIntegrationTest" --rerun-tasks
./gradlew test --tests "*Reservation*" --tests "*FlywayMigrationIntegrationTest" --rerun-tasks
```

2026-11-09 관련 회귀 실행 후 생성된 XML에서 다음 결과를 확인했다.

| 범위 | Suite 수 | 테스트 수 | 결과 |
| --- | ---: | ---: | --- |
| 예약 관련 + Flyway 회귀 전체 실행 | 43 | **261** | 실패·오류·건너뜀 0 |
| 포함된 관리자 목록 조건별 회귀 | 1 | 20 | 모두 통과 |
| 포함된 관리자 계획·유지 비용 측정 | 1 | 1 | 통과 |
| 포함된 Flyway migration 검증 | 1 | 4 | 모두 통과 |

하위 행은 261개에 포함되므로 별도로 합산하지 않는다. 관리자 계획 테스트 시작은 01:20:50 KST, 조건별 회귀는 01:21:25 KST, Flyway는 01:24:02 KST였다. 프로젝트 전체 테스트·운영 DB 적용·HTTP 부하 검증으로 확대하지 않는다.

최신 유지 비용 요약은 실행 폴더의 `maintenance/summary.txt`, 크기는 `maintenance/sizes-at-100000-rows.txt`에서 볼 수 있다. 원본 결과 파일은 Git에 첨부하지 않는다. 앞 절의 시간은 해당 단계에서 확인한 실행값이며, 회귀 재실행 값으로 섞어서 바꾸지 않는다.

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListPlanIntegrationTest.java)
- [조건별 회귀 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListQueryIntegrationTest.java)
- [공통 유지 비용 측정 도우미](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationIndexMaintenanceMeasurement.java)
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
| 조건부 count join·동일 조건 재측정 | 완료. 전체·RESERVED count 감소, 시설·날짜 건수 유지 |
| 조건별 회귀 20개 | 완료 |
| V5 저장 공간·쓰기 비용 | 완료. 한계와 관찰 결과 기록 |
| 관련 기존 예약·Flyway 전체 회귀 | 완료. 43개 Suite·261개 통과 |
| PR CI·최종 채택 | 미진행 |

수치는 당시 사용자가 실행한 실제 결과에서 확인했다. 원본 로그·XML·해시 첨부 대신 핵심 지표와 대표 계획을 본문에 남겼으며, 문서 정리 중 테스트를 재실행하지 않았다.
