# 관리자 예약 목록 실행 계획 분석 및 조회 최적화

> **KAN-133 · 관리자 예약 목록 실행 계획 분석 및 조회 최적화**
>
> 현재 단계: **개선 전 실행 계획 수집·증거 보존 완료. 쿼리·인덱스 변경과 개선 후 측정은 아직 진행하지 않음.**
>
> 전체 목록은 예약 10만 건 스캔과 정렬, RESERVED count는 10만 건 스캔과 슬롯 조회 25,000회를 수행했다.
> 현재 우선 검토 대상은 전체 목록과 상태 조건 count다. 이 문서에 이후 실험과 결과를 이어서 기록한다.

## 1. 배경과 작업 범위

KAN-129에서 관리자 예약 목록의 슬롯·어트랙션 fetch join을 적용하여 N+1을 개선했다. KAN-130에서는 내 예약 목록용 인덱스 `(user_id, reservation_status, id)`를 추가했다.

관리자 목록은 회원 조건 없이 전체 예약을 조회하거나 상태·어트랙션·날짜로 검색한다. 이번에는 관리자 목록·count 각각의 실행 계획을 측정하여 기존 인덱스와 join의 처리 비용을 확인한다.

원본 비즈니스 규칙 **BR-RSV-20**은 관리자의 전체 예약 필터·검색·페이징 조회를 요구한다. 인덱스 선택·허용 실행 시간·합성 데이터 규모는 이번 실험 조건이며 별도의 비즈니스 정책으로 확정하지 않는다.

관련 코드:

- [측정 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListPlanIntegrationTest.java): `findAdminReservationAll_recordsBaselineAndExecutionPlans()`
- [Repository](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java): `findAdminReservationByCondition()`
- [Service](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java): `findAdminReservationAll()`
- [응답 DTO](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationResponse.java): `from()`

초기 측정은 다음 세 조건의 **목록과 count를 각각** 대상으로 한다.

1. 필터 없는 전체 목록
2. RESERVED 목록
3. 어트랙션 ID 3 + 2026-10-04 날짜 조건의 목록

페이지 번호는 0, 크기는 10, 정렬은 `status DESC, id DESC`다. 관리자 상세 조회·Cursor Pagination·count 생략·HTTP 부하 테스트는 포함하지 않는다.

## 2. 환경과 데이터

- Testcontainers MySQL **8.4.11**: 실행 시 VERSION()으로 확인
- 테스트 전용 DB `test`, 격리 수준 `REPEATABLE-READ`
- `@DataJpaTest`, `@ActiveProfiles("test")`
- Flyway V1~V4 적용, Hibernate `ddl-auto=validate`
- 2차 캐시·Query Cache 비활성화
- 실제 Service·Repository·DTO 변환 실행
- 목록 조회가 사용하지 않는 UserService·TicketService·AttractionSlotService만 mock 처리
- JDBC batch로 fixture를 준비하고 커밋한 뒤 예약·슬롯·어트랙션 통계 갱신
- 측정 중 인덱스·데이터 변경 없음

합성 데이터 구성:

```text
회원: 200명
예약: 100,000건 (회원별 500건)
어트랙션: 10개
슬롯: 500개
기간: 2026-10-01부터 10일
하루 슬롯: 50개 (어트랙션별 5개)
슬롯별 예약: 200건
상태: RESERVED / COMPLETED / CANCELLED / NO_SHOW 각각 25%

전체 조건의 전체 건수: 100,000건
RESERVED 조건의 전체 건수: 25,000건
어트랙션·날짜 조건의 전체 건수: 1,000건
```

FK 관계와 활성 예약 UNIQUE 제약을 유지하는 조회용 합성 이력이다. 실제 업무 API로 생성한 운영 이력이나 트래픽을 대표한다고 가정하지 않는다. 다른 상태 비율이나 특정 어트랙션·날짜의 편중은 후속 측정 대상이다.

## 3. 측정 방법과 통과 기준

1. 실제 Service의 PreparedStatement SQL과 JDBC setter·바인딩 값을 수집한다.
2. 전체 건수·ID 순서·응답 필드·페이지 정보를 검증한다.
3. 수집한 실제 SQL에 동일 바인딩을 재사용하여 EXPLAIN을 기록한다.
4. 쿼리별 EXPLAIN ANALYZE 1회 워밍업 후 5회 결과를 개별 파일에 저장한다.

응답 검증용 기준 SQL과 계획 분석용 SQL을 구분한다. 실행 계획은 Service에서 수집한 SQL을 사용하며, 조건을 추측하여 다른 SQL을 만들어 측정하지 않는다.

이번 테스트는 **성능 수집용 테스트**다. 응답 정확성과 결과 수집을 검증하지만 특정 시간·인덱스 선택·Sort 존재 여부로 실패시키지 않는다. 따라서 통과했다고 최적화할 부분이 없다는 뜻은 아니다.

EXPLAIN ANALYZE는 실제 쿼리를 실행한다. 이번 결과는 Service·검증 조회와 워밍업의 캐시 영향을 포함하는 **warm-cache 측정**이다. 공식 설명: [MySQL EXPLAIN Statement](https://dev.mysql.com/doc/refman/8.4/en/explain.html).

시간은 최상위 노드 `actual time=시작..완료`의 완료 값(ms)을 사용한다. 내부 노드 시간을 합산하지 않는다. `rows`는 `loops`와 함께 해석하며, 반복 노드는 행 수만 보고 전체 처리량으로 단정하지 않는다.

## 4. 개선 전 실행 결과

테스트 시작: **2026-11-08 22:47:57 KST**. XML timestamp: `2026-11-08T13:47:57.794Z`.

```text
tests=1, failures=0, errors=0, skipped=0
scenarioCount=3, repetitions=5
```

각 Service 호출의 실제 SELECT는 목록 1개 + count 1개였으며, 응답·전체 건수·정렬·페이지 검증이 통과했다. 상세 조건별 N+1 회귀 테스트 전체를 다시 실행한 결과로 확대 해석하지 않는다.

### 4-1. 전체 목록: 10만 건 스캔과 정렬

[실제 계획](evidence/kan-133/before/all-content-analyze-1.txt)의 핵심 흐름:

```text
Table scan on reservations: rows=100000, loops=1
→ Sort: reservation_status DESC, id DESC
→ 슬롯 PK 조회: rows=1, loops=10
→ 어트랙션 PK 조회: rows=1, loops=10
→ 10건 반환
```

최종 반환 10건에 비해 예약 입력량이 크고 별도 Sort가 존재한다. 이 실험에서는 전체 목록의 첫 번째 개선 후보로 본다.

### 4-2. 전체 count: 슬롯 500개와 예약 10만 건 집계

[실제 계획](evidence/kan-133/before/all-count-analyze-1.txt):

```text
슬롯 covering index scan: rows=500, loops=1
→ 슬롯별 예약 covering index lookup: rows=200, loops=500
→ 100,000건 집계
```

실제 SQL은 어트랙션 join까지 포함하지만 이 실행 계획에서는 어트랙션 접근 노드가 나타나지 않았다. SQL에 선언한 join과 실제 실행 노드를 구분한다.

정확한 전체 건수를 반환하는 count는 목록의 LIMIT 10과 요구하는 처리량이 다르다. 목록처럼 반드시 10건만 읽는 것을 목표로 삼지 않으며, join·인덱스 경로의 개선 여지를 따로 검토한다.

### 4-3. RESERVED 목록: 역방향 PK 조회 후 조기 종료

[실제 계획](evidence/kan-133/before/reserved-content-analyze-1.txt):

```text
PRIMARY 역방향 scan: rows=40, loops=1
→ RESERVED 필터: rows=10, loops=1
→ 슬롯·어트랙션 PK 조회: 각각 loops=10
→ 10건 반환
```

별도 Sort 없이 필요한 10건을 찾았다. 이번 상태 분포에서는 빠르게 종료했지만, RESERVED가 드물거나 ID 구간에 편중된 경우까지 같은 결과를 보장하지 않는다.

### 4-4. RESERVED count: 10만 건 스캔과 슬롯 조회

[실제 계획](evidence/kan-133/before/reserved-count-analyze-1.txt):

```text
예약 table scan: rows=100000, loops=1
→ RESERVED 필터: rows=25000, loops=1
→ 슬롯 PK covering lookup: rows=1, loops=25000
→ 25,000건 집계
```

목록이 빠르게 끝나더라도 count가 같은 방식으로 끝나는 것은 아니다. 이 조건에서는 상태 검색 경로와 count의 join 비용을 두 번째 개선 후보로 본다.

### 4-5. 어트랙션·날짜 목록과 count

[목록 계획](evidence/kan-133/before/attraction-date-content-analyze-1.txt):

```text
idx_attraction_slots_attraction_starts 범위 조회: 슬롯 rows=5, loops=1
→ 슬롯 FK 인덱스로 예약 조회: rows=200, loops=5
→ 1,000건을 입력으로 정렬
→ 10건 반환
```

[count 계획](evidence/kan-133/before/attraction-date-count-analyze-1.txt)은 동일 슬롯 범위와 예약 FK 인덱스를 covering 경로로 사용하여 1,000건을 집계했다.

기존 슬롯 인덱스가 어트랙션·날짜 범위를 좁히고 있다. 이 조건도 Sort 비용은 있지만, 이번 초기 결과에서는 앞선 두 병목보다 낮은 우선순위로 둔다.

## 5. 쿼리별 반복 시간

워밍업을 제외한 5회 최상위 노드 완료 시간이며 단위는 ms다.

```text
전체 목록
반복: 65.1, 65.1, 66.0, 65.8, 65.1
중앙값: 65.1

전체 count
반복: 23.7, 23.8, 23.6, 23.8, 23.8
중앙값: 23.8

RESERVED 목록
반복: 0.0621, 0.0948, 0.1030, 0.0613, 0.0630
중앙값: 0.0630

RESERVED count
반복: 33.4, 35.6, 35.4, 34.0, 36.9
중앙값: 35.4

어트랙션·날짜 목록
반복: 2.66, 2.74, 2.69, 2.83, 2.72
중앙값: 2.72

어트랙션·날짜 count
반복: 0.292, 0.263, 0.299, 0.273, 0.251
중앙값: 0.273
```

중앙값은 정렬한 5개 값의 세 번째 값이다. 서로 다른 SQL의 시간을 합쳐 Service 전체 응답 시간으로 표현하지 않는다. 5회 합성 측정만으로 운영 장애나 통계적으로 유의한 성능 차이가 입증됐다고 결론 내리지 않는다.

## 6. 우선순위와 다음 실험

1. 전체 목록의 10만 건 스캔·Sort 비용을 줄일 수 있는 조회 경로 검토
2. RESERVED count의 상태 검색·join 비용 검토
3. 두 변경이 전체 count와 어트랙션·날짜 조회에도 미치는 영향 확인

아직 후보 인덱스나 쿼리 변경을 확정하지 않았다. 필요한 경우 한 변경씩 적용하여 효과를 분리하고, 같은 데이터·조건·반복 횟수로 비교한다. 인덱스를 추가한다면 저장 공간·쓰기 유지 비용도 검토한다.

기존 응답·필터·정렬·페이징·전체 건수 계약을 유지한다. count join을 바꾸는 실험에서는 FK·NULL 조건·필터와 결과 동등성을 먼저 확인한다. 스키마 변경은 [프로젝트 Flyway 규칙](../database/migration.md)에 따라 엔티티와 새 migration으로 반영한다.

## 7. 개선 전 증거 보존

원본 폴더:

```text
build/reports/admin-reservation-list-query/run-1794145708821-3d24036b-f96a-49fd-99f6-eac02fbbf6a4/
```

결과 파일 57개와 테스트 XML 1개, 총 **58개 파일**을 `docs/troubleshooting/evidence/kan-133/before/`에 복사했다. 모든 원본·보존본 SHA256 일치를 확인하고 [해시 목록](evidence/kan-133/before/SHA256SUMS.txt)을 추가했다.

- [환경](evidence/kan-133/before/environment.txt)
- [데이터 분포](evidence/kan-133/before/data-distribution.txt)
- [예약 인덱스](evidence/kan-133/before/indexes-reservations.txt)
- [슬롯 인덱스](evidence/kan-133/before/indexes-attraction_slots.txt)
- [어트랙션 인덱스](evidence/kan-133/before/indexes-attractions.txt)
- [Flyway V1~V4 이력](evidence/kan-133/before/flyway-history.txt)
- [테스트 XML](evidence/kan-133/before/test-results.xml)

보존 폴더에는 실제 SQL·바인딩, 테이블 DDL, EXPLAIN, 워밍업 및 반복 계획도 포함된다. 개선 후 결과는 별도 폴더에 보존하고 `before`를 덮어쓰지 않는다.

## 8. 진행 상태와 후속 기록

- [x] KAN-129·130이 반영된 코드에서 시작
- [x] 실제 Service·DTO·MySQL·Flyway 기반 측정 테스트 작성
- [x] 세 조건의 목록·count 실행 계획 수집
- [x] 응답·정렬·전체 건수·페이지 정보 검증 통과
- [x] 개선 전 증거와 XML·해시 보존
- [ ] 첫 번째 개선 후보와 선택 이유 기록
- [ ] 동일 조건의 개선 후 측정
- [ ] 다른 조건의 성능 변화와 회귀 확인
- [ ] 추가 인덱스가 있다면 저장 공간·쓰기 비용 검토
- [ ] 관련 기존 예약·Flyway 테스트와 PR CI 확인

```text
선택한 변경: 미정
변경 파일·migration: 없음
개선 후 실행 계획·시간: 미측정
최종 채택 여부: 미정
```

실행 명령:

```bash
./gradlew test --tests "*AdminReservationListPlanIntegrationTest" --rerun-tasks
```

테스트는 사용자가 실행했고, 에이전트는 실제 결과 XML과 계획 파일을 읽어 확인했다. 문서 작성 중 빌드·테스트·DB 쿼리를 실행하지 않았으며 프로덕션 코드를 변경하지 않았다.
