# 내 예약 목록 실행 계획 분석 및 인덱스 최적화

> **KAN-130 · 내 예약 목록 실행 계획 분석 및 인덱스 최적화**
>
> 현재 단계: **인덱스 변경 전 측정과 증거 보존 완료. 인덱스 후보 적용·개선 후 측정은 아직 진행하지 않음.**
>
> 기존 인덱스는 회원별 검색에 사용되고 있다. 다만 목록 정렬과 상태 필터를 위해 추가 처리가 발생했다.
> 이 문서는 이후 인덱스 후보 비교와 회귀 검증 결과를 이어서 기록한다.

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

## 5. 현재 판단과 다음 실험

**측정으로 확인한 사실:** 기존 인덱스로 회원 검색이 가능하지만, 첫 페이지 10건을 반환하기 위해 해당 회원의 예약 500건을 읽고 정렬했다. 상태 검색도 500건을 읽은 뒤 필터링했다.

**다음 검토 대상:** 회원 조건·상태 필터·기본 정렬을 함께 지원하는 복합 인덱스 후보를 비교할 수 있다. 아직 후보를 확정하거나 엔티티·migration·DB 인덱스를 변경하지 않았다.

이 수치만으로 운영 장애나 반드시 개선해야 하는 응답 지연이 있다고 결론 내리지 않는다. 추가 인덱스의 조회 이득과 저장 공간·쓰기 유지 비용을 함께 평가한다. 효과가 작거나 부작용이 크면 추가하지 않는 선택도 가능하다.

다음 측정에서는 같은 데이터 분포·조건·워밍업·반복 횟수를 유지하고 다음을 비교한다.

- 선택 인덱스와 별도 Sort 존재 여부
- 예약 인덱스 조회 행 수와 상태 Filter 입력·출력 행 수
- 슬롯·어트랙션 join 처리량
- 목록·count 각각의 반복 실행 시간
- 응답과 전체 건수 유지 여부
- 인덱스 크기와 쓰기 비용 검토

실제 스키마 변경을 반영할 때는 [프로젝트 Flyway 규칙](../database/migration.md)에 따라 엔티티와 새 migration을 함께 보완한다. 기존 V1·V2·V3는 수정하지 않는다. 새 번호는 작업 시점의 최신 dev를 다시 확인한다.

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

## 7. 진행 상태와 개선 후 기록 공간

- [x] KAN-126 fetch join 반영 코드에서 시작
- [x] 실제 MySQL·Flyway 스키마의 측정 테스트 작성
- [x] 합성 예약 10만 건의 데이터 분포·인덱스·SQL 기록
- [x] 회원 조건 2종 × 목록·count 각각 5회 측정
- [x] 개선 전 자료·테스트 XML·해시 보존
- [ ] 인덱스 후보 선택과 적용 이유 기록
- [ ] 동일 조건의 개선 후 측정
- [ ] 조회 결과·기존 테스트 회귀 확인
- [ ] 쓰기·저장 공간 비용과 채택 여부 판단

```text
후보 인덱스: 미정
적용 파일·migration: 없음
개선 후 조회 행 수·정렬·시간: 미측정
최종 채택 여부: 미정
```

실행 명령:

```bash
./gradlew test --tests "*ReservationListIndexIntegrationTest" --rerun-tasks
```

테스트는 사용자가 실행했고, 에이전트는 결과 XML·실행 계획을 읽어 확인했다. 문서 작성 과정에서 DB 쿼리·빌드·테스트를 실행하지 않았으며 프로덕션 코드를 변경하지 않았다.
