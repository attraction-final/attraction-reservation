# 관리자 예약 목록 N+1 측정 및 개선

> **KAN-129 · 관리자 예약 목록 N+1 측정 및 개선**
>
> 실제 Service와 DTO 변환까지 측정한 결과, SELECT 수는 10건 조회에서 **22개 → 2개**, 50건 조회에서 **102개 → 2개**로 줄었다.
> content 쿼리의 슬롯·어트랙션에 fetch join을 적용했으며, 측정 및 조건별 회귀 테스트 **11개가 모두 통과**했다.
> 예약 관련 기존 테스트 전체 실행과 부하 테스트 결과는 아직 확인하지 않았다.

## 1. 문제와 작업 범위

관리자 예약 목록의 기존 조회 흐름은 다음과 같았다.

```text
ReservationService.findAdminReservationAll()
→ Repository에서 예약 목록과 전체 건수 조회
→ SearchAdminReservationResponse.from()으로 DTO 변환
→ 슬롯의 시작·종료 시각 접근
→ 슬롯에 연결된 어트랙션 이름 접근
```

Repository는 슬롯과 어트랙션을 일반 join으로 연결했지만, 연관 엔티티를 함께 로딩하는 fetch join은 사용하지 않았다. 일반 join으로 검색 조건을 연결하는 것과 DTO 변환에 필요한 연관 엔티티를 로딩하는 것은 별개다.

관련 파일:

- [ReservationCustomRepositoryImpl](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java): `findAdminReservationByCondition()`
- [ReservationService](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java): `findAdminReservationAll()`
- [SearchAdminReservationResponse](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationResponse.java): `from()`
- [측정·회귀 테스트](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationListQueryIntegrationTest.java)

원본 비즈니스 규칙 BR-RSV-20은 관리자의 전체 예약 필터·검색·페이징 조회를 요구한다. 이번 변경은 이 기능을 유지하면서 조회 비용을 줄이는 구현 보완이다. SELECT 2개라는 수치는 문서의 비즈니스 정책이 아니라 현재 목록·count 구조에 맞춘 테스트 목표다.

이번 범위는 **관리자 예약 목록**이다. 관리자 상세 조회, 내 예약 목록, 인덱스·실행 계획, count 생략, Cursor Pagination, 부하 테스트는 포함하지 않는다.

## 2. 재현 환경과 측정 방법

### 환경

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers MySQL `mysql:8.4`: 프로젝트 migration 문서의 운영 RDS 버전에 맞춤
- Hibernate `ddl-auto=create-drop`, Flyway 비활성화: 해당 테스트에서만 적용
- Hibernate Statistics 활성화, 2차 캐시·Query Cache 비활성화
- 실제 ReservationService·Repository·DTO 변환 사용
- 목록 조회에서 사용하지 않는 UserService·TicketService·AttractionSlotService만 mock 처리
- 기본 테스트 트랜잭션으로 데이터 준비·조회 후 rollback

이 테스트는 엔티티 기준으로 만든 스키마에서 조회 동작과 SQL 수를 검사한다. migration SQL과 엔티티의 일치 여부를 검증하는 FlywayMigrationIntegrationTest를 대체하지 않는다.

### 기본 데이터와 조회 조건

- 두 회원의 RESERVED 예약을 번갈아 저장: 각 25건, 총 50건
- 각 예약은 서로 다른 슬롯·어트랙션 사용
- 회원별 방문일에 입장 처리된 티켓 사용
- 방문일: 2026-11-09
- 슬롯은 11:00부터 10분 간격으로 구성하고 슬롯별 예약 인원은 1명
- 검색 필터 없음: 회원·어트랙션·상태·날짜 모두 null
- 페이지 번호 0, 크기 10과 50 비교
- 정렬: `status DESC, id DESC`

연관 대상을 서로 다르게 만들어 같은 슬롯·어트랙션이 1차 캐시에서 재사용되면서 N+1이 작게 보이는 것을 방지했다.

### 측정 구간

```text
fixture 저장
→ flush()
→ clear()
→ Hibernate Statistics.clear()
→ 실제 관리자 목록 Service 호출
→ DTO 변환 완료
→ 통계 기록
```

- `statementCount`: `getPrepareStatementCount()`. 측정 구간은 읽기 경로만 실행하므로 SELECT 수로 해석한다.
- `queryExecutionCount`: Hibernate 쿼리 실행 통계. 지연 엔티티 조회를 포함한 전체 SQL 수와 동일하게 해석하지 않는다.
- `entityFetchCount`: 추가 엔티티 fetch를 확인하는 보조 통계.
- `durationMs`: Service 호출과 DTO 변환의 단일 실행 시간. fixture 준비 시간은 제외한다.

Statistics는 SessionFactory 단위 통계이므로 동일 SessionFactory에 병렬 작업이 있으면 결과에 섞일 수 있다. 대상 클래스를 단독 실행하여 측정한다.

## 3. 개선 전 재현 결과

테스트 시작 시각은 **2026-11-06 21:50:19 KST**이며, 당시 XML timestamp는 `2026-11-06T12:50:19.727Z`였다.

```text
tests=1, failures=1, errors=0, skipped=0

event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=10 count=10 totalElements=50 statementCount=22 queryExecutionCount=2 entityFetchCount=20 durationMs=278.312
event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=50 count=50 totalElements=50 statementCount=102 queryExecutionCount=2 entityFetchCount=100 durationMs=235.485
```

실패는 다음 세 쿼리 수 검증에서 발생했다.

- 10건 조회: 목표 SELECT 2개 이하, 실제 22개
- 50건 조회: 목표 SELECT 2개 이하, 실제 102개
- 페이지 크기가 커져도 SELECT 수가 같아야 함: 실제 22개와 102개

쿼리 수 검증 전에 실행한 응답 필드·정렬·전체 건수·페이지 정보 검증은 통과했다. 따라서 조회 결과 오류와 SQL 증가를 구분할 수 있었다.

측정치와 DTO의 연관 접근 경로는 다음 구조와 일치한다.

```text
예약 목록 SELECT 1개
+ 전체 건수 SELECT 1개
+ 슬롯 조회 N개
+ 어트랙션 조회 N개
= 2 + 2N
```

일반적으로 말하는 N+1 문제이며, 이번 데이터에서는 두 단계의 지연 로딩으로 N이 두 번 더해졌다.

**증거 보존 범위:** 위 내용은 당시 에이전트가 실제 실패 XML에서 확인한 지표와 실패 메시지를 기록한 것이다. 문서 작성 시점에는 해당 경로가 개선 후 결과로 바뀌었고, 현재 작업 트리의 별도 개선 전 XML 보존본은 확인하지 못했다. 원본 XML을 보존했다고 표현하거나 기록된 수치로 원본 XML을 재생성하지 않는다.

## 4. 개선 구현

사용자가 `findAdminReservationByCondition()`의 content 쿼리를 다음과 같이 수정했다.

```java
.selectFrom(reservation)
.join(reservation.attractionSlot, attractionSlot)
.fetchJoin()
.join(attractionSlot.attraction, attraction)
.fetchJoin()
```

두 to-one 관계를 fetch join으로 로딩하여 DTO 변환 중 슬롯 시각·어트랙션 이름에 접근할 때 추가 SELECT가 필요하지 않도록 했다. 각 `.fetchJoin()`은 바로 앞 join에 적용한다.

일반 join이 이미 존재했던 content 쿼리에 fetch join 옵션만 추가하여 기존 Service와 DTO 변환 구조를 유지했다. DTO projection은 이번 작업에 적용하지 않았다.

다음 항목은 기존대로 유지했다.

- 회원·어트랙션·상태·날짜 검색 조건
- 정렬과 offset·limit
- 응답 DTO 구조
- count 쿼리의 일반 join과 필터

컬렉션 fetch join은 추가하지 않았으며 count 쿼리에도 fetch join을 추가하지 않았다.

## 5. 개선 후 측정과 회귀 검증

### 동일 조건 재측정

개선 직후 최초 재측정은 **2026-11-06 22:23:31 KST**에 시작했다. XML timestamp는 `2026-11-06T13:23:31.520Z`였고, 측정 테스트 1개가 통과했다.

```text
event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=10 count=10 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=248.149
event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=50 count=50 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=35.375
```

### 회귀 테스트 추가 후 최종 결과

최종 실행 시작은 **2026-11-06 22:32:10 KST**, XML timestamp는 `2026-11-06T13:32:10.120Z`다.

```text
tests=11, failures=0, errors=0, skipped=0

event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=10 count=10 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=6.452
event=ADMIN_RESERVATION_LIST_QUERY_MEASURED pageSize=50 count=50 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=14.581
```

케이스별 검증 내용:

- 기본 측정 1개: 필터 없는 10건·50건 조회, 응답 필드·전체 건수·정렬·페이지 정보
- 회원 필터 1개: 지정한 회원의 예약 25건만 반환, 필터 적용된 전체 건수
- 어트랙션 필터 1개: 같은 어트랙션의 여러 슬롯·여러 회원 예약 반환
- 상태 필터 3개: CANCELLED·COMPLETED·NO_SHOW 각각 조회, RESERVED 목록에서 변경된 예약 제외
- 날짜 필터 1개: 지정 날짜 예약만 조회하고 전날·다음 날 예약 제외
- 복합 조건 1개: 회원·어트랙션·RESERVED·날짜 동시 적용
- 빈 결과 1개: 내용·전체 건수·전체 페이지 0, 다음 페이지 없음
- 페이지 2개: 두 번째 페이지와 마지막 부분 페이지의 ID 순서·전체 건수·페이지 정보

추가 회귀 조회도 flush·clear·통계 초기화 후 실제 Service를 호출하며, 각 조회의 SELECT가 2개 이하인지 함께 검증한다.

### 전후 비교의 의미

- 10건 조회: SELECT **22개 → 2개**, 추가 엔티티 fetch **20개 → 0개**
- 50건 조회: SELECT **102개 → 2개**, 추가 엔티티 fetch **100개 → 0개**
- 두 경우 모두 목록 1개 + count 1개로 유지되어 조회 건수에 비례하던 추가 SELECT가 제거됐다.

위 시간 값은 단일 실행의 참고 자료다. 워밍업·테스트 실행 순서·환경 영향을 통제한 반복 성능 측정이 아니므로 응답 속도 개선율을 계산하지 않는다. 회귀 케이스가 늘어난 뒤 시간 값이 더 작아진 것도 추가 최적화 효과로 해석하지 않는다.

## 6. 증거 파일과 실행 방법

- [개선 후 측정·회귀 결과 XML](evidence/kan-129/after-admin-reservation-list.xml)
- 원본 위치: `build/test-results/test/` 아래 해당 클래스의 `TEST-*.xml`
- 실제 파일명에 부가 문자열이 있어 `*AdminReservationListQueryIntegrationTest*.xml` 패턴으로 결과를 확인했다.
- 보존본은 복사 시 원본과 SHA256이 일치함을 확인했다.

```text
SHA256
F7081FF0DEF04040F9EFDC9AF08F470D542A482C5A14B2013258352A8090D77A
```

실행 명령:

```bash
./gradlew test --tests "*AdminReservationListQueryIntegrationTest" --rerun-tasks
```

Docker가 필요하다. 테스트는 사용자가 실행했고, 에이전트는 생성된 XML을 읽어 결과를 확인했다. 문서 작성 과정에서 빌드·테스트를 실행하지 않았다.

## 7. 남은 확인과 후속 작업

- [x] 관리자 목록의 실제 N+1 재현
- [x] content 쿼리 fetch join 적용
- [x] 동일 조건에서 SELECT 수가 2개로 유지됨을 확인
- [x] 조건별 조회·빈 결과·페이지 회귀 검증 11개 통과
- [x] 개선 후 XML과 전후 지표 문서화
- [ ] 관련 기존 예약 테스트 전체 실행 결과 확인
- [ ] PR CI 결과 확인

날짜 필터의 전날·다음 날 제외는 검증했지만, 자정 경계값을 각각 직접 구성한 테스트는 이번 목록에 포함하지 않았다. 상태가 혼합된 페이지의 모든 정렬 조합이나 모든 필터 조합을 전수 검증한 것도 아니다.

별도 후속 후보는 실행 계획·인덱스·count 비용 측정과 부하 테스트다. 이번 결과로 운영 환경의 p95 응답 시간, 최대 처리량 또는 동시 요청 안정성을 입증한 것으로 기록하지 않는다.
