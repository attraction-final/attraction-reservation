# 내 예약 목록의 N+1 측정 및 개선 기록

> **KAN-126 · 내 예약 목록의 연관 엔티티 조회 비용 측정 및 개선**
>
> 현재 단계: **fetch join 적용 후 동일 조건 재측정 통과. 추가 필터·페이지 회귀 검증은 남아 있음.**
>
> 실제 Service의 DTO 변환까지 실행한 결과, SELECT 수는 10건 조회에서 22개 → 2개, 50건 조회에서 102개 → 2개로 줄었다.
> 이 문서는 이후 개선 구현, 동일 조건의 재측정, 회귀 결과를 이어서 업데이트한다.

## 1. 문제와 작업 범위

내 예약 목록은 예약 ID와 상태뿐 아니라 슬롯 시각과 어트랙션 이름도 반환한다.

개선 전 조회 흐름:

```text
ReservationService.findReservationAll()
→ ReservationCustomRepositoryImpl.findReservationAllByCondition()
→ 예약 목록 SELECT + 전체 건수 SELECT
→ SearchReservationResponse.from()으로 DTO 변환
→ 각 예약의 attractionSlot 접근
→ 각 슬롯의 attraction.name 접근
```

개선 전 목록 쿼리는 연관 엔티티를 함께 로딩하지 않아, DTO 변환 단계에 추가 SELECT가 발생하는지 실제로 측정했다. 단순히 Querydsl을 사용하거나 일반 join을 작성하는 것만으로 연관 엔티티가 함께 초기화되는 것은 아니다.

관련 코드:

- [ReservationService](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java): 실제 목록 조회와 DTO 변환
- [ReservationCustomRepositoryImpl](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java): 목록·count 쿼리
- [SearchReservationResponse](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationResponse.java): 슬롯·어트랙션 정보 접근

이번 범위는 **내 예약 목록**이다. 관리자 목록, 인덱스, count 생략 최적화, Cursor Pagination, 부하 테스트는 별도 후속 검토 대상으로 둔다. API 응답과 회원별 조회 범위·필터·정렬·페이징 계약은 유지한다.

## 2. 측정 환경과 데이터

[ReservationListQueryIntegrationTest](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationListQueryIntegrationTest.java)를 추가했다.

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers MySQL `mysql:9.6`
- `ddl-auto=create-drop`
- Hibernate `generate_statistics=true`
- 2차 캐시·Query Cache 비활성화: 해당 테스트에만 적용
- 실제 ReservationService·Repository·DTO 변환 사용
- 목록 조회가 사용하지 않는 UserService·TicketService·AttractionSlotService만 mock 처리
- 기본 테스트 트랜잭션 안에서 fixture 생성·조회 후 rollback

### fixture

- 본인 예약 50건, 타인 예약 1건
- 본인 예약마다 서로 다른 슬롯과 어트랙션 사용
- 회원별 입장 처리된 티켓 사용
- 탑승 날짜는 2026-11-09, 상태는 RESERVED
- 본인 예약의 슬롯은 11:00부터 10분 간격으로 배치하여 서로 겹치지 않음
- 슬롯마다 예약 인원 1명으로 구성
- 타인 예약은 본인 예약보다 큰 ID로 저장하여 소유자 조건 누락을 검출

모든 예약이 같은 슬롯·어트랙션을 공유하면 1차 캐시가 같은 엔티티를 재사용해 추가 조회가 작게 보일 수 있다. 따라서 연관 대상도 서로 다르게 구성했다.

### 조회 조건

```text
회원: 본인 userId
상태: RESERVED
날짜: 2026-11-09
페이지 번호: 0
페이지 크기: 10 / 50
정렬: status DESC, id DESC
전체 건수: 본인 예약 50건
```

## 3. 측정 방법과 지표 해석

각 페이지 크기를 측정하기 전에 다음을 수행한다.

```text
entityManager.flush()
→ entityManager.clear()
→ Hibernate Statistics.clear()
→ 실제 Service 호출 및 DTO 변환 완료
→ statementCount와 관련 통계 기록
```

이렇게 fixture INSERT와 이전 측정의 1차 캐시·통계 영향을 제외한다. 두 조회는 같은 데이터와 조건에서 페이지 크기만 바꾼다.

### 기록한 지표

- `statementCount`: `Statistics.getPrepareStatementCount()`. 이번 측정 구간은 읽기 전용 경로만 실행하므로 준비한 SQL statement 수를 SELECT 수로 사용한다.
- `queryExecutionCount`: Hibernate 쿼리 실행 통계. 지연 엔티티 로딩을 모두 포함한 전체 SELECT 수로 해석하지 않는다.
- `entityFetchCount`: 엔티티 fetch 통계. 추가 연관 엔티티 로딩의 증가를 확인하는 보조 지표다.
- `durationMs`: Service 호출과 DTO 변환이 끝날 때까지의 단일 실행 시간. fixture 준비 시간은 제외한다.

측정 로그는 `event=RESERVATION_LIST_QUERY_MEASURED`로 검색한다. Statistics는 SessionFactory 단위 통계이므로 다른 작업이 동시에 같은 SessionFactory를 사용하면 측정에 섞일 수 있다. 현재 결과는 대상 통합 테스트를 단독 실행한 결과다.

## 4. 개선 전 실제 결과

테스트 시작 시각은 **2026-11-06 17:35:23 KST**이며, XML timestamp는 `2026-11-06T08:35:23.291Z`다.

```text
tests=1, failures=1, errors=0

10건 조회:
statementCount=22
queryExecutionCount=2
entityFetchCount=20
durationMs=450.097

50건 조회:
statementCount=102
queryExecutionCount=2
entityFetchCount=100
durationMs=414.030
```

원본 측정 로그:

```text
event=RESERVATION_LIST_QUERY_MEASURED pageSize=10 count=10 totalElements=50 statementCount=22 queryExecutionCount=2 entityFetchCount=20 durationMs=450.097
event=RESERVATION_LIST_QUERY_MEASURED pageSize=50 count=50 totalElements=50 statementCount=102 queryExecutionCount=2 entityFetchCount=100 durationMs=414.030
```

목록·전체 건수 조회는 두 경우 모두 2개인데, 연관 엔티티 fetch가 20개에서 100개로 늘었다. 측정치와 DTO 접근 경로는 다음 구조와 일치한다.

```text
예약 목록 SELECT 1개
+ 전체 건수 SELECT 1개
+ 예약마다 슬롯 SELECT N개
+ 각 슬롯의 어트랙션 SELECT N개
= 2 + 2N
```

일반적으로 말하는 N+1 문제의 형태이며, 이번 데이터에서는 두 단계의 지연 로딩으로 N이 두 번 더해졌다.

### 실패한 검증

- 10건 조회: 개선 목표 SELECT 2개 이하 / 실제 22개
- 50건 조회: 개선 목표 SELECT 2개 이하 / 실제 102개
- 두 페이지의 SELECT 수가 같아야 함 / 실제 22개와 102개

하나의 테스트에서 위 세 SQL 수 검증이 실패했다. 해당 검증에 앞서 수행한 현재 fixture의 응답 데이터·ID 순서·타인 예약 제외·전체 건수·페이지 정보 검증은 통과했다. 다양한 상태·날짜 조합 전체의 회귀를 완료한 것으로 확대 해석하지 않는다.

10건의 시간이 50건보다 길게 측정됐으므로 이 수치로 처리 속도나 개선율을 계산하지 않는다. 초기화·워밍업·실행 순서·환경 차이를 통제한 반복 성능 측정이 아니다. 우선 **조회 건수에 비례하는 SQL 증가를 제거하는 것**을 개선 지표로 삼는다.

## 5. 개선 전 증거 보존

Gradle의 `build` 결과는 다음 실행이나 `clean`으로 덮어써질 수 있어, 현재 실패 XML을 문서 옆에 복사해 보존했다.

- [개선 전 원본 XML 보존본](evidence/kan-126/before-reservation-list.xml)
- 원래 경로: `build/test-results/test/TEST-com.example.attractionreservation.domain.reservation.service.ReservationListQueryIntegrationTest.xml`
- 복사 시 원본과 보존본의 SHA256 일치 확인

```text
SHA256
2363A38051CD91C2F09C74D9CF5EA3111335CF400AB4B071F75FE66A63DBB8F5
```

보존본에는 최초 실패 내용과 Standard output이 포함된다. 개선 후 결과로 덮어쓰지 않는다. 이후 측정 결과는 별도 파일로 저장하고 아래 내용을 업데이트한다.

## 6. 개선 구현과 재측정 결과

- [x] 실제 MySQL과 Service의 DTO 변환까지 포함한 재현 테스트 작성
- [x] 서로 다른 연관 엔티티를 사용하는 데이터로 SQL 증가 확인
- [x] 개선 전 XML·지표 보존
- [x] 목록 쿼리에 필요한 슬롯·어트랙션 fetch join 적용
- [x] 기존 count 쿼리 유지 및 현재 fixture의 API 응답 검증 통과
- [x] 동일 데이터·페이지 크기·정렬·캐시 조건으로 재측정
- [x] 추가 연관 조회가 건수에 비례하지 않는지 확인
- [ ] 상태·날짜 필터 및 다른 페이지를 포함한 회귀 검증
- [ ] 관련 기존 테스트 실행 결과 기록

### 선택한 방식과 변경 범위

사용자가 `ReservationCustomRepositoryImpl.findReservationAllByCondition()`의 content 쿼리에 다음 두 fetch join을 적용했다.

```java
.selectFrom(reservation)
.join(reservation.attractionSlot, attractionSlot)
.fetchJoin()
.join(attractionSlot.attraction, attraction)
.fetchJoin()
```

각 `.fetchJoin()`은 바로 앞의 join에 적용한다. 슬롯과 어트랙션을 목록 조회 시 함께 로딩하여 DTO 변환 중 추가 SELECT가 필요하지 않도록 했다.

기존 Service의 엔티티 → DTO 변환을 유지하면서 필요한 to-one 관계만 함께 로딩하는 방식을 선택했다. DTO projection도 대안이지만 이번 작업에는 적용하지 않았다. 컬렉션 fetch join은 추가하지 않았다.

소유자·상태·날짜 조건, 정렬, offset·limit 및 count 쿼리는 기존 코드 그대로 유지했다. 관리자 목록과 상세 조회는 이번 수정 범위에 포함하지 않았다.

### 개선 후 실제 결과

테스트 시작 시각은 **2026-11-06 19:55:47 KST**이며, XML timestamp는 `2026-11-06T10:55:47.340Z`다.

```text
tests=1, failures=0, errors=0, skipped=0

10건 조회:
statementCount=2
queryExecutionCount=2
entityFetchCount=0
durationMs=300.041

50건 조회:
statementCount=2
queryExecutionCount=2
entityFetchCount=0
durationMs=39.235
```

원본 측정 로그:

```text
event=RESERVATION_LIST_QUERY_MEASURED pageSize=10 count=10 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=300.041
event=RESERVATION_LIST_QUERY_MEASURED pageSize=50 count=50 totalElements=50 statementCount=2 queryExecutionCount=2 entityFetchCount=0 durationMs=39.235
```

### 전후 비교와 확인 범위

- 10건 조회: SELECT **22개 → 2개**, 추가 엔티티 fetch **20개 → 0개**
- 50건 조회: SELECT **102개 → 2개**, 추가 엔티티 fetch **100개 → 0개**
- 두 경우 모두 목록 1개 + count 1개로, 조회 건수에 비례하던 추가 SELECT를 제거했다.
- 현재 fixture의 응답 필드, 예약 ID 순서, 타인 예약 제외, 전체 건수, 페이지 정보 검증도 통과했다.

이번 결과는 RESERVED·지정 날짜·첫 페이지에서 크기 10/50을 비교한 것이다. 다른 상태·날짜 조합, 빈 결과, 두 번째 이후 페이지와 관련 기존 테스트 전체의 통과까지 확인한 것은 아니다. 이 항목들은 후속 회귀 검증으로 남긴다.

단일 실행 시간은 참고 자료로만 보존한다. 워밍업과 실행 순서를 통제하지 않았으므로 `450.097 → 300.041ms`, `414.030 → 39.235ms`를 서비스 응답 속도 개선율로 해석하지 않는다.

### 개선 후 증거 보존

- [개선 후 원본 XML 보존본](evidence/kan-126/after-reservation-list.xml)
- 원래 경로: `build/test-results/test/TEST-com.example.attractionreservation.domain.reservation.service.ReservationListQueryIntegrationTest.xml`
- 복사 시 원본과 보존본의 SHA256 일치 확인
- 개선 전 XML은 그대로 보존

```text
SHA256
6C0DA9C58BBF5701340A7F64DA069693D78781CBE3C86AFDCDECC6E3DB6FDB48
```

실행 명령:

```bash
./gradlew test --tests "*ReservationListQueryIntegrationTest" --rerun-tasks
```

테스트는 사용자가 실행했고, 에이전트는 생성된 XML을 읽어 결과를 확인했다. 문서 작성 과정에서 테스트를 재실행하지 않았다.

## 7. 후속 성능 검증

조회 SQL 수 개선 후 관리자 목록, 실행 계획·인덱스·count 비용을 각각 측정한다. 이후 별도 부하 테스트에서 동시 사용자 수에 따른 응답 시간·처리량·오류율·DB 부하를 비교한다.

이번 단일 통합 테스트의 시간이나 SQL 수만으로 실제 서비스의 p95 응답 시간, 최대 처리량, 부하 상황의 안정성이 개선됐다고 결론 내리지 않는다.
