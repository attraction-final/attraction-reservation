# 내 예약 목록 실행 계획 분석 및 인덱스 최적화

> **KAN-130 · 내 예약 목록 실행 계획 분석 및 인덱스 최적화**
>
> 현재 단계: **복합 인덱스 적용, 조회 계획 비교, 저장 공간·쓰기 비용 측정 완료. 관련 기존 테스트 전체 회귀 검증은 남아 있음.**
>
> 새 인덱스 적용 후 두 목록의 Sort가 없어졌고, 예약 인덱스 조회량은 500건에서 10건으로 줄었다.
> 예약 10만 건에서 인덱스 크기는 약 4.52MiB로 추정됐으며, 합성 상태 UPDATE batch의 중앙값 증가는 약 40.28ms/1,000건이었다.

## 1. 목적과 범위

KAN-126에서 내 예약 목록의 슬롯·어트랙션 fetch join을 적용하여 N+1을 개선했다. 이번에는 쿼리 개수가 아니라 **각 목록·count 쿼리가 어떤 인덱스를 사용하고 얼마나 많은 행을 처리하는지** 측정한다.

인덱스 선택과 실행 시간은 이번 구현의 측정 대상이다. 아래 데이터 규모·분포는 실험 조건이며 운영 요구사항으로 확정한 수치가 아니다.

현재 범위:

- 내 예약 목록의 회원 조건 + 기본 정렬
- 내 예약 목록의 회원·RESERVED 조건 + 기본 정렬
- 각 조건의 목록 쿼리와 count 쿼리를 별도로 측정
- 첫 페이지, 크기 10

날짜 필터, 관리자 목록, 깊은 offset, count 생략, Cursor Pagination, HTTP 부하 테스트는 이번 초기 측정에 포함하지 않는다.

관련 파일:

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationListIndexIntegrationTest.java): `findReservationAll_recordsIndexBaselineAndExecutionPlans()`
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java): `findReservationAllByCondition()`
- [Service](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java): `findReservationAll()`
- [Flyway 작성 규칙](../database/migration.md)

## 2. 환경과 데이터

### 실행 환경

- Testcontainers MySQL **8.4.11**: 실제 실행 시 `VERSION()`으로 확인
- 격리된 컨테이너 DB `test`, 기본 격리 수준 `REPEATABLE-READ`
- `@DataJpaTest`, `@ActiveProfiles("test")`
- Flyway 활성화 + Hibernate `ddl-auto=validate`
- V1·V2·V3 적용 이력 보존
- 2차 캐시·Query Cache 비활성화
- 실제 Service·Repository·DTO 변환 실행
- 조회에서 사용하지 않는 UserService·TicketService·AttractionSlotService만 mock 처리
- 테스트 전용 DataSource를 사용하여 로컬·공유 DB에 연결하지 않음

엔티티로 새 스키마를 만드는 방식 대신 Flyway로 스키마를 구성했다. 따라서 기록한 인덱스는 해당 migration들을 적용한 테스트 DB의 실제 인덱스다. 운영 DB의 현재 인덱스를 직접 조사한 결과로 해석하지 않는다.

### 데이터 분포

```text
회원: 200명
예약: 100,000건
회원별 예약: 500건
슬롯: 500개
어트랙션: 10개
방문 날짜: 2026-10-01부터 10일
회원별 상태 분포: RESERVED / COMPLETED / CANCELLED / NO_SHOW 각각 125건
대상 회원 ID: 101
페이지 번호: 0
페이지 크기: 10
정렬: status DESC, id DESC
날짜 조건: 없음
```

예약 ID는 슬롯별로 여러 회원의 데이터가 섞이도록 구성했다. 티켓·주문·슬롯 등 FK 관계와 활성 예약 UNIQUE 제약을 유지하는 조회용 합성 이력이며, 실제 업무 API로 10만 건을 생성한 결과는 아니다.

회원별 예약 500건과 상태별 25%라는 균등 분포는 실험 조건이다. 실제 회원의 이용 이력, 데이터 편중, 운영 트래픽을 대표한다고 가정하지 않는다.

## 3. 측정 방법

1. 빈 테스트 DB에 fixture를 JDBC batch로 저장하고 커밋한다.
2. 예약·슬롯·어트랙션 테이블에 `ANALYZE TABLE`을 수행하여 통계를 갱신한다.
3. 실제 Service가 실행한 PreparedStatement의 SQL과 JDBC setter·바인딩 값을 수집한다.
4. 소유자·목록 ID 순서·응답·페이지 정보를 검증한다.
5. 수집한 SQL에 동일 JDBC 바인딩을 재사용하여 `EXPLAIN`과 `EXPLAIN ANALYZE`를 실행한다.
6. 쿼리별 `EXPLAIN ANALYZE` 1회 워밍업 후 5회 결과를 각각 저장한다.

필터를 추측해 다른 SQL을 만들거나 바인딩 값을 문자열로 치환하여 측정하지 않는다. 응답 검증용 기준 ID 조회는 별도로 수행하며, 계획 분석에는 수집한 실제 Service SQL을 사용한다.

`EXPLAIN ANALYZE` 자체도 실제 SELECT를 실행한다. Service 호출과 검증 조회 역시 캐시에 영향을 주므로 이번 결과는 **워밍업된 상태의 계획 분석**이다. cold-cache 비교나 독립된 서버 환경의 벤치마크가 아니다.

시간은 실행 계획 **최상위 노드의 `actual time=시작..완료` 중 완료 값(ms)**을 기록한다. 내부 노드 시간을 합산하거나 이 값을 HTTP 응답 시간으로 취급하지 않는다. `rows`와 `loops`는 노드별로 해석하며, 아래 인덱스 조회 행 수는 해당 노드가 `loops=1`인 경우다.

공식 참고: [MySQL 8.4 EXPLAIN Statement](https://dev.mysql.com/doc/refman/8.4/en/explain.html)

## 4. 개선 전 실제 결과

테스트 시작 시각은 **2026-11-06 23:49:08 KST**이며, XML timestamp는 `2026-11-06T14:49:08.345Z`다.

```text
tests=1, failures=0, errors=0, skipped=0
```

이 테스트의 통과는 데이터·응답 검증과 실행 계획 수집 성공을 뜻한다. 특정 인덱스를 사용하거나 특정 시간 이하로 실행되어야 통과하도록 작성하지 않았다.

### 기존 인덱스

`SHOW INDEX FROM reservations`에서 다음 인덱스를 확인했다.

- `PRIMARY (id)`
- 예약 번호 UNIQUE
- `uk_reservations_user_slot_active (user_id, attraction_slot_id, active_reservation)`
- 슬롯 FK 인덱스
- 티켓 FK 인덱스

현재 회원 검색에는 `uk_reservations_user_slot_active`가 사용됐다. 따라서 인덱스가 전혀 없거나 전체 10만 건을 항상 스캔하는 상태는 아니다. 회원 검색 뒤 상태 검사·정렬에 필요한 처리가 남아 있는지를 구분해서 본다.

### 회원별 목록

실제 SQL의 주요 조건:

```sql
WHERE r.user_id = ?
ORDER BY r.reservation_status DESC, r.id DESC
LIMIT ?, ?
```

바인딩 값은 `userId=101`, `offset=0`, `limit=10`이었다. 전체 SELECT 컬럼과 fetch join은 [원본 SQL](evidence/kan-130/before/user-only-content-sql.txt)에 보존했다.

계획의 핵심 흐름:

```text
기존 복합 UNIQUE 인덱스로 해당 회원의 예약 500건 읽기
→ reservation_status DESC, id DESC 정렬
→ 슬롯·어트랙션 PK 조회
→ 10건 반환
```

[첫 번째 실제 계획](evidence/kan-130/before/user-only-content-analyze-1.txt)에서 예약 인덱스 조회는 `rows=500 loops=1`, 슬롯·어트랙션 PK 조회는 각각 `rows=1 loops=10`이었다. 정렬 노드가 존재하며, 최종 반환은 10건이다.

### 회원·RESERVED 목록

실제 SQL의 주요 조건:

```sql
WHERE r.user_id = ? AND r.reservation_status = ?
ORDER BY r.reservation_status DESC, r.id DESC
LIMIT ?, ?
```

바인딩은 `101`, `RESERVED`, `0`, `10`이었다.

```text
기존 복합 UNIQUE 인덱스로 해당 회원의 예약 500건 읽기
→ RESERVED 조건으로 125건 남기기
→ id DESC 정렬
→ 슬롯·어트랙션 PK 조회
→ 10건 반환
```

[첫 번째 실제 계획](evidence/kan-130/before/user-reserved-content-analyze-1.txt)에는 예약 인덱스 조회 `rows=500 loops=1`, 상태 Filter `rows=125 loops=1`, Sort가 기록됐다. 상태가 고정되어 계획의 정렬 키는 `id DESC`로 표시됐다.

### 두 count 쿼리

- 회원별 count: 기존 인덱스의 **Covering index lookup**으로 500건을 집계했다.
- 회원·RESERVED count: 기존 인덱스로 500건을 읽은 뒤 상태 조건으로 125건을 남기고 집계했다.

회원별 count는 이미 인덱스만으로 처리되는 반면, 상태 조건이 있는 count에는 별도 Filter가 있었다. 두 count를 같은 수준의 병목으로 취급하지 않는다.

### 5회 반복 시간

모두 최상위 노드 완료 시간이며 단위는 ms다.

```text
회원별 목록
반복: 1.140, 1.230, 1.180, 1.450, 1.460
중앙값: 1.230

회원별 count
반복: 0.143, 0.105, 0.104, 0.104, 0.103
중앙값: 0.104

회원·RESERVED 목록
반복: 1.250, 1.130, 1.080, 1.130, 1.200
중앙값: 1.130

회원·RESERVED count
반복: 0.897, 0.968, 0.912, 0.891, 0.913
중앙값: 0.912
```

중앙값은 각 쿼리의 5개 완료 시간을 정렬한 세 번째 값이다. 통계적 유의성이나 운영 성능을 입증하는 결과는 아니다.

## 5. 인덱스 적용과 조회 계획 비교

**측정으로 확인한 사실:** 기존 인덱스로 회원 검색이 가능하지만, 첫 페이지 10건을 반환하기 위해 해당 회원의 예약 500건을 읽고 정렬했다. 상태 검색도 500건을 읽은 뒤 필터링했다.

사용자가 Reservation의 `@Table.indexes`에 다음 일반 복합 인덱스를 정의했다. 기존 활성 예약 UNIQUE 제약은 유지했다.

```text
이름: idx_reservations_user_status_id
컬럼 순서: user_id, reservation_status, id
```

회원 조건을 앞에 두고, 상태 필터와 기본 정렬에 사용하는 컬럼을 뒤에 두는 후보를 실험했다. [V4 migration](../../src/main/resources/db/migration/V4__add_reservation_user_status_id_index.sql)으로 실제 DB에 같은 인덱스를 생성했다.

```sql
CREATE INDEX idx_reservations_user_status_id
    ON reservations (user_id, reservation_status, id);
```

`ddl-auto=validate`는 엔티티의 `@Index`를 DB에 생성하지 않는다. 이번 테스트 DB의 실제 인덱스 생성은 Flyway V4가 수행했으며, 이력의 `success=true`와 실행 계획의 인덱스 사용을 확인했다. 기존 V1·V2·V3는 변경하지 않았다.

### 개선 직후 재측정

실행 시작: **2026-11-07 00:41:41 KST**, XML timestamp `2026-11-06T15:41:41.991Z`.

```text
tests=1, failures=0, errors=0, skipped=0
```

- 회원별 목록: 새 인덱스의 역방향 조회 사용, 예약 조회 노드 `rows=10 loops=1`, Sort 없음
- 회원·RESERVED 목록: 새 인덱스에서 회원·상태 조건을 함께 사용, 예약 조회 노드 `rows=10 loops=1`, Sort 없음
- 회원별 count: 새 인덱스의 Covering index lookup으로 500건 집계
- 회원·RESERVED count: 새 인덱스의 Covering index lookup으로 해당 상태 125건 집계

상태 조건이 있는 계획에는 Filter 노드가 남아 있지만, 인덱스 조회 단계부터 `user_id=101, reservation_status='RESERVED'`를 사용한다. 개선 전처럼 500건을 읽고 상태를 추리는 구조와 구분한다. 슬롯·어트랙션의 PK 조회는 목록당 각각 10회였다.

5회 완료 시간의 중앙값 비교:

```text
회원별 목록:           1.230ms → 0.165ms
회원·RESERVED 목록:    1.130ms → 0.129ms
회원별 count:          0.104ms → 0.0983ms
회원·RESERVED count:   0.912ms → 0.0534ms
```

회원별 count는 개선 전에도 Covering index lookup으로 처리되어 시간 차이가 작았다. 이 비교는 합성 데이터·warm-cache에서의 두 실행 결과이며 운영 응답 속도 개선율로 일반화하지 않는다.

## 6. 개선 전 증거 보존

원본 결과 폴더:

```text
build/reports/reservation-list-index/run-1793976577515-de982905-b659-41fd-aa15-9dd92acc105e/
```

위 폴더의 결과 39개와 해당 실행의 테스트 XML 1개, 총 **40개 파일**을 `docs/troubleshooting/evidence/kan-130/before/`에 복사했다. 각 파일의 원본·보존본 SHA256 일치를 확인했고, [해시 목록](evidence/kan-130/before/SHA256SUMS.txt)도 추가했다.

- [환경·측정 조건](evidence/kan-130/before/environment.txt)
- [실제 데이터 분포](evidence/kan-130/before/data-distribution.txt)
- [예약 인덱스](evidence/kan-130/before/indexes-reservations.txt)
- [슬롯 인덱스](evidence/kan-130/before/indexes-slots.txt)
- [Flyway 이력](evidence/kan-130/before/flyway-history.txt)
- [테스트 XML](evidence/kan-130/before/test-results.xml)

폴더에는 실제 SQL·바인딩 값, 테이블 DDL, EXPLAIN, 워밍업 계획, 쿼리별 반복 계획이 포함된다. 개선 후 자료는 별도 폴더에 저장하고 `before`를 덮어쓰지 않는다.

## 7. 인덱스 크기와 쓰기 유지 비용

실행 시작: **2026-11-07 01:14:07 KST**, XML timestamp `2026-11-06T16:14:07.167Z`.

```text
tests=1, failures=0, errors=0, skipped=0
```

기존 측정 테스트에 [쓰기 비용 측정 도우미](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationIndexMaintenanceMeasurement.java)를 연결했다. 독립된 새 테스트 여러 개가 아니라, 한 테스트 실행 안에서 조회 계획과 유지 비용을 이어서 수집한다.

### 비교 구성

- 원본 예약 테이블은 그대로 유지하고 같은 초기 데이터 10만 건을 복제 테이블 두 개에 구성
- 후보 인덱스만 유무를 다르게 하고 나머지 인덱스 정의가 일치하는지 검증
- `CREATE TABLE LIKE`가 복제하지 않는 FK는 양쪽에 명시적으로 재생성
- 기존 UNIQUE와 Generated Column 유지
- 신규 슬롯을 미리 준비하여 INSERT의 활성 예약 중복 방지 제약과 충돌하지 않게 구성
- 1회 워밍업 후 5회 측정, 반복마다 인덱스 유무의 실행 순서를 교대
- 예약 1,000건 RESERVED INSERT와 기존 RESERVED 1,000건의 CANCELLED UPDATE를 각각 측정
- UPDATE에는 취소 사유·취소 시각·updated_at 변경 포함
- 각 batch는 별도 트랜잭션이며 시간에 JDBC batch 및 트랜잭션 시작·커밋을 포함
- fixture·DDL·통계 갱신·검증 조회 시간은 측정 구간에서 제외

각 반복은 서로 다른 ID·슬롯·UPDATE 대상을 사용한다. 행 수는 양쪽 모두 동일하게 100,000건에서 106,000건까지 증가하며, 데이터를 삭제·원복하는 시간은 측정하지 않는다.

### 인덱스 크기

`ANALYZE TABLE` 이후 `mysql.innodb_index_stats`의 `stat_name=size`에 기록된 페이지 수와 `@@innodb_page_size`로 계산했다. 해당 통계 조회에는 테스트 컨테이너의 root 계정을 사용했고, 접속 정보는 결과에 기록하지 않았다.

```text
예약 100,000건 기준
새 인덱스: 289 pages × 16,384 bytes
추정 크기: 4,734,976 bytes = 4.515625 MiB (약 4.52MiB)
```

106,000건과 상태 변경 이후에는 287 pages, 4,702,208 bytes로 기록됐다. 행 수만으로 인덱스 크기가 선형 증가한다고 단정하지 않으며, 이 수치를 정확한 파일 실사용량으로 취급하지 않는다. 페이지 구성과 통계에 기반한 추정치다.

### 쓰기 시간

워밍업을 제외한 5회 측정의 중앙값이며, 단위는 **1,000건 batch + 커밋의 ms**다.

```text
INSERT
인덱스 없음: 50.7905ms
인덱스 있음: 51.7038ms
중앙값 차이: +0.9133ms

상태 UPDATE
인덱스 없음: 176.0352ms
인덱스 있음: 216.3117ms
중앙값 차이: +40.2765ms
```

INSERT의 인덱스 있는 조건에는 120.0087ms 측정값도 포함되어 변동이 있었다. 중앙값만으로 원자료의 변동을 숨기지 않도록 [전체 반복 CSV](evidence/kan-130/maintenance-run/maintenance/trials.csv)를 보존했다.

UPDATE 시간 증가는 상태 컬럼이 포함된 새 인덱스의 유지 비용이 증가하는 방향과 맞지만, 측정된 차이를 순수 인덱스 비용으로 확정하지 않는다. 캐시·페이지 배치·백그라운드 I/O 등도 영향을 줄 수 있다.

검증한 결과:

- INSERT 대상 1,000건이 RESERVED로 저장됨
- UPDATE 대상 1,000건이 CANCELLED이며 취소 사유·시각과 Generated Column 값이 기대대로 반영됨
- 두 복제 테이블의 최종 행 수와 상태 분포 일치
- 원본 예약 테이블 100,000건 유지

업무 Service, 슬롯 인원 복구, HTTP, 동시 잠금 경합을 포함하는 쓰기 부하 테스트는 아니다. 이 batch 시간을 실제 예약 생성·취소 요청의 응답 시간으로 해석하지 않는다.

## 8. 개선 후 증거 보존

### 최초 인덱스 적용 후 조회 비교

원본 폴더:

```text
build/reports/reservation-list-index/run-1793979732215-094b94a0-45ac-4697-914c-5fefda7c9c24/
```

결과 39개와 테스트 XML 1개를 `evidence/kan-130/after/`에 보존했고 원본·보존본 SHA256 일치를 확인했다.

- [해시 목록](evidence/kan-130/after/SHA256SUMS.txt)
- [V4 적용 이력](evidence/kan-130/after/flyway-history.txt)
- [회원별 목록 계획](evidence/kan-130/after/user-only-content-analyze-1.txt)
- [회원·RESERVED 목록 계획](evidence/kan-130/after/user-reserved-content-analyze-1.txt)
- [테스트 XML](evidence/kan-130/after/test-results.xml)

### 유지 비용 측정 실행

원본 폴더:

```text
build/reports/reservation-list-index/run-1793981676370-03768366-55ca-4ca5-ad13-558bc2bc1915/
```

중첩 `maintenance/`를 포함한 결과 46개와 테스트 XML 1개, 총 **47개 파일**을 `evidence/kan-130/maintenance-run/`에 보존했다. 원본·보존본 SHA256 일치를 확인하고 해시 목록을 별도로 추가했다. 기존 `before/`와 `after/`는 덮어쓰지 않았다.

- [해시 목록](evidence/kan-130/maintenance-run/SHA256SUMS.txt)
- [초기 인덱스 크기](evidence/kan-130/maintenance-run/maintenance/sizes-at-100000-rows.txt)
- [쓰기 후 크기](evidence/kan-130/maintenance-run/maintenance/sizes-after-writes.txt)
- [쓰기 측정 전체 CSV](evidence/kan-130/maintenance-run/maintenance/trials.csv)
- [중앙값 요약](evidence/kan-130/maintenance-run/maintenance/summary.txt)
- [측정 방법과 한계](evidence/kan-130/maintenance-run/maintenance/method.txt)
- [테스트 XML](evidence/kan-130/maintenance-run/test-results.xml)

## 9. 현재 판단과 남은 검증

이번 실험 조건에서는 목록의 정렬·조회 행 수와 상태 조건 count에 개선이 확인됐다. 추가 저장 공간과 상태 UPDATE 비용도 관찰됐다. **현재 변경에는 후보 인덱스를 유지하고 기존 테스트 회귀 검증과 PR 검토를 진행**한다.

이는 운영 환경의 읽기·쓰기 비율이나 허용 지연을 확정한 최종 운영 검증이 아니다. 이번 단일 스레드 합성 실험으로 운영 p95, 처리량, 동시 예약·취소 성능을 보장하지 않는다. 부하 테스트에서는 실제 요청 비율과 데이터 편중도 별도로 확인한다.

진행 상태:

- [x] KAN-126 fetch join 반영 코드에서 시작
- [x] 실제 MySQL·Flyway 스키마의 측정 테스트 작성
- [x] 합성 예약 10만 건의 데이터 분포·인덱스·SQL 기록
- [x] 회원 조건 2종 × 목록·count 각각 5회 측정
- [x] 개선 전 자료·테스트 XML·해시 보존
- [x] 인덱스 후보 선택과 엔티티·V4 반영
- [x] 동일 조건의 개선 후 측정
- [x] 측정 테스트의 응답·소유자·정렬·페이지 검증 통과
- [x] 인덱스 크기·쓰기 비용 측정과 근거 기록
- [x] 개선 후·유지 비용 측정의 증거 보존
- [ ] 관련 기존 예약 테스트 전체 회귀 확인
- [ ] FlywayMigrationIntegrationTest 결과 확인
- [ ] PR CI·리뷰 및 최종 채택 확인

```text
후보 인덱스: idx_reservations_user_status_id (user_id, reservation_status, id)
적용: Reservation.java의 @Index, V4__add_reservation_user_status_id_index.sql
계획: 두 목록 Sort 제거, 예약 인덱스 조회량 500 → 10
현재 판단: 후보 유지, 기존 테스트·PR 검증 진행
운영 부하 검증: 후속 작업
```

실행 명령:

```bash
./gradlew test --tests "*ReservationListIndexIntegrationTest" --rerun-tasks
```

다음 회귀 검증 명령:

```bash
./gradlew test --tests "*Reservation*" --tests "*FlywayMigrationIntegrationTest" --rerun-tasks
```

위 회귀 명령의 결과는 아직 확인하지 않았다. Hibernate validate만으로 인덱스 정의까지 검증되는 것은 아니므로, 인덱스 이름·컬럼 순서는 엔티티·V4·실제 인덱스 자료도 함께 확인한다.

테스트는 사용자가 실행했고, 에이전트는 결과 XML·실행 계획을 읽어 확인했다. 문서 작성 과정에서 DB 쿼리·빌드·테스트를 실행하지 않았으며 프로덕션 코드를 변경하지 않았다.
