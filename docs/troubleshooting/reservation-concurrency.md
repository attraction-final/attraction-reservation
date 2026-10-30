# 예약 동시성 트러블슈팅 기록

> **KAN-87 · 코드 및 테스트 검증 완료 / 기존 DB 적용 미실행**
>
> 실제 MySQL에서 중복·시간 충돌을 재현한 뒤 회원 행 잠금과 활성 예약 DB 유니크 제약을 적용했다. 최신 XML에서 동시성·회귀·rollback 테스트 6개와 DB 제약 테스트 4개를 포함한 예약 도메인 175개 테스트의 통과를 확인했다. 기존 DB에 적용할 migration SQL은 작성했지만 실행하지 않았다.

## 1. 진행 현황

| 단계 | 상태 | 확인 내용 |
| --- | --- | --- |
| 동일 슬롯 중복 예약 재현 | 완료 | 동일 회원의 요청 10개가 모두 성공 |
| 서로 다른 슬롯의 시간 충돌 재현 | 완료 | 겹치는 두 슬롯의 요청이 모두 성공, 각 슬롯 인원 1명 |
| 해결 방식 비교 및 선택 | 완료 | 두 요청이 공유하는 회원 행의 비관적 잠금 선택 |
| 회원 잠금 적용 및 재현 테스트 재검증 | 통과 확인 | 동일 슬롯 요청 10개 중 성공 1개, 겹치는 슬롯 요청 2개 중 성공 1개 |
| 활성 예약 DB 유니크 제약 | 통과 확인 | Generated Column과 유니크 제약 매핑, 실제 MySQL 직접 저장 검증 |
| 시간 미충돌 동시 예약 | 통과 확인 | 같은 회원의 겹치지 않는 두 슬롯 예약 모두 성공 |
| 취소 후 재예약 검증 | 통과 확인 | 취소 이력 유지, 새 예약 생성, 최종 예약 인원 1명 |
| 다른 회원의 동일 슬롯 동시 예약 | 통과 확인 | 성공·활성 예약·예약 인원 각각 2 |
| 정원 확보 후 실패 시 rollback | 통과 확인 | save 호출에 실패 주입, 실패 전 인원 1 → rollback 후 0 |
| 예약 도메인 기존 테스트 | 통과 확인 | 최신 XML 집계: 21개 클래스, 175개 테스트, 실패·오류·건너뜀 0 |
| migration SQL 작성 | 완료 | 사전 확인·컬럼 및 제약 추가·적용 결과 확인 SQL 작성 |
| 기존 DB 적용 및 migration 실행 검증 | 미실행 | 로컬 DB에는 테이블 없음. Entity 기반 생성 테스트와 별도 작업 |

이 문서에는 **테스트로 확인한 사실**, **코드에 근거한 분석**, **앞으로 검증할 사항**을 구분해 누적한다. 아직 수행하지 않은 테스트는 완료로 기록하지 않는다.

## 2. 문제와 비즈니스 규칙

예약 생성은 다음 순서로 진행된다.

```text
회원·티켓·슬롯 검증
    → 같은 회원의 동일 슬롯 활성 예약 확인
    → 같은 회원의 다른 활성 예약과 시간 충돌 확인
    → 슬롯 정원 확보
    → 예약 저장 및 transaction commit
```

중복·충돌 조회와 저장 사이에 다른 요청이 같은 검사를 통과하면, 순차 요청에서는 지켜지던 규칙이 동시 요청에서는 깨질 수 있다.

### 문서에 명시된 요구사항

출처: 원본 참고 자료 `sources/비즈니스 규칙.md`의 예약 규칙·취소 규칙·동시성 규칙. 원본 문서는 변경하지 않았다.

| 규칙 | 요구사항 | 이번 작업과의 관계 |
| --- | --- | --- |
| BR-RSV-03 | 같은 회원의 같은 슬롯 중복 예약 금지, 활성 예약 기준 유니크 제약 | 동일 슬롯 중복 방지 |
| BR-RSV-04 | 같은 회원의 활성 예약끼리 시간 중복 금지 | 다른 어트랙션의 슬롯까지 포함 |
| BR-RSV-13 | 취소된 예약은 복구하지 않고 새 예약 생성 | 취소 이력이 정상 재예약을 막으면 안 됨 |
| BR-RSV-30 | 동시 요청에도 정원 초과 방지 | 회원 중복 방지와 별도로 유지 |
| BR-RSV-31 | 동일 회원·동일 슬롯 동시 요청은 DB 유니크 제약으로 1건만 성공 | DB 제약 설계 필요 |
| BR-RSV-32 | 잔여 정원 = 정원 − 예약 완료 건수 | 실패 요청의 rollback과 인원 정합성 확인 |

현재 중복·충돌 조회는 `RESERVED` 상태를 대상으로 한다.

### 관련 코드

경로와 줄 번호는 최초 기록 시점 기준이며, 이후 수정 시 달라질 수 있다.

| 파일 | 메서드 및 위치 | 역할 |
| --- | --- | --- |
| [ReservationService.java](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java) | `createReservation()`, 110–131줄 | 중복·충돌 검사 후 정원 확보 및 저장 |
| [ReservationCustomRepositoryImpl.java](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationCustomRepositoryImpl.java) | `existsReservedByUser_IdAndSlot_Id()`, `existsTimeConflictReservation()` | 실제 DB에서 중복·시간 충돌 조회 |
| [AttractionSlotService.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/service/AttractionSlotService.java) | `tryReservationCapacity()` | 조건부 UPDATE 후 엔티티 refresh |
| [Reservation.java](../../src/main/java/com/example/attractionreservation/domain/reservation/entity/Reservation.java) | 테이블 매핑 | 최초 재현 시 활성 예약 중복 제약 없음. 이후 Generated Column과 유니크 제약 추가 |

실제 운영 DB에 별도로 적용된 제약은 확인하지 않았다. 최초 재현은 변경 전 엔티티 매핑으로 생성된 Testcontainers DB에서 수행됐다. 이후 추가한 활성 예약 제약과 검증 결과는 6.7절에 기록한다.

## 3. 재현 ① 동일 회원·동일 슬롯 동시 예약

### 3.1 목적

정원이 충분한 상황에서 같은 회원이 같은 슬롯에 동시에 10번 요청해도 **예약은 1건만 생성되어야 한다**는 규칙을 검증한다.

정원은 요청 수보다 많은 20명으로 설정했다. 정원 부족에 따른 실패와 회원 중복 방지에 따른 실패를 구분하기 위해서다.

### 3.2 환경과 데이터

| 항목 | 설정 및 확인 내용 |
| --- | --- |
| 기록일 | 2026-10-29 |
| 브랜치 | `fix/KAN-87-reservation-concurrency` |
| 기록 시점 HEAD | `10759c4408fb46ec78898c97f01d2955e1cba53b` |
| 테스트 코드 상태 | 기록 시점에는 새 테스트 파일이 아직 untracked 상태. HEAD만으로 재현 테스트까지 복원할 수 없음 |
| DB | Testcontainers MySQL, 이미지 태그 `mysql:9.6` |
| 데이터 접근 | 실제 JPA·Querydsl Repository 및 예약 Service 사용 |
| Connection pool | 최대 20개 |
| 회원·티켓 | 동일한 ACTIVE 회원과 본인 소유 IN_USE 종일권 |
| 기준 시각 | 2026-10-29 14:00 KST로 고정 |
| 슬롯 | 당일 16:00–16:30, 운영 중인 어트랙션, OPEN 상태 |
| 정원·초기 예약 인원 | 정원 20명, reservedCount 0명 |
| 요청 | 동일 회원·티켓·슬롯으로 10개 |
| DB isolation level | 테스트에서 별도 지정하지 않음. 실행 DB의 실제 session 값은 아직 수집하지 않음 |

`mysql:9.6`은 설정한 이미지 태그다. 정확한 이미지 digest와 DB patch version은 이번 기록에서 수집하지 않았다.

### 3.3 테스트 구성

테스트 파일: [ReservationConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationConcurrencyIntegrationTest.java)

테스트 메서드:

```java
createReservation_SameUserAndSlotConcurrently_OnlyOneSucceeds()
```

1. `TransactionTemplate`으로 준비 데이터를 저장하고 commit한다. 작업 스레드가 준비 데이터를 조회할 수 있도록 하기 위해서다.
2. 테스트 자체의 transaction은 `Propagation.NOT_SUPPORTED`로 비활성화한다.
3. 스레드 10개를 준비하고 `CountDownLatch`로 시작 시점을 맞춘다.
4. 각 스레드에서 실제 `ReservationService.createReservation()`을 호출한다. Service의 transaction은 요청별로 독립적으로 실행된다.
5. 현재 시각을 고정하는 static mock은 스레드별로 적용한다. 중복 조회·정원 UPDATE·예약 INSERT는 mock으로 대체하지 않는다.
6. 모든 요청이 종료된 후 별도 transaction으로 활성 예약 수와 슬롯 예약 인원을 조회한다.
7. 테스트가 실패하더라도 해당 테스트가 생성한 데이터를 정리한다.

`PasswordEncoder`와 `RefreshTokenRepository`는 UserService 생성에 필요한 의존성으로 mock을 사용한다. 예약 생성 경로에서 해당 동작을 검증하는 테스트는 아니다.

### 3.4 실행 방법과 로그 확인

Docker를 실행한 뒤 프로젝트 루트의 Git Bash에서 실행한다.

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest.createReservation_SameUserAndSlotConcurrently_OnlyOneSucceeds"
```

이미 실행된 결과가 캐시되어 실제 재실행이 필요하다면 다음 명령을 사용한다.

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest.createReservation_SameUserAndSlotConcurrently_OnlyOneSucceeds" --rerun-tasks
```

실행 후 아래 HTML 파일을 브라우저로 열어 테스트 실패 내용과 Standard output을 확인한다.

```text
build/reports/tests/test/index.html
```

핵심 로그만 검색하려면 다음 명령을 사용한다.

```bash
grep -n "동시 예약 결과" build/test-results/test/*ReservationConcurrency*.xml
```

### 3.5 기대 결과와 실제 결과

| 검증 항목 | 기대 | 실제 | 판정 |
| --- | ---: | ---: | --- |
| 성공한 요청 수 | 1 | 10 | 중복 요청 차단 실패 |
| 해당 회원·슬롯의 RESERVED 예약 수 | 1 | 10 | 활성 예약 중복 생성 |
| 슬롯 reservedCount | 1 | 10 | 동일 회원이 여러 자리 확보 |
| 실패한 요청 수 | 9 | 0 | 모든 요청 성공 |
| reservedCount와 실제 활성 예약 수 | 일치 | 10 = 10 | 이번 실행에서는 일치 |

실제 출력:

```text
동시 예약 결과: 요청=10, 성공=10, 활성 예약=10, reservedCount=10, 실패=[]
```

JUnit 결과:

```text
tests=1, skipped=0, failures=1, errors=0
```

기대값과 실제값의 차이로 실패한 assertion은 다음 세 가지다.

```text
[성공한 요청 수]              expected: 1L / actual: 10L
[DB에 저장된 활성 예약 수]    expected: 1L / actual: 10L
[슬롯 예약 인원]              expected: 1  / actual: 10
```

**판정: 동일 회원·동일 슬롯의 중복 예약 문제를 실제 MySQL에서 재현했다.** 테스트 실행 자체가 중단된 것이 아니라, 올바른 동작을 기대하는 검증이 실패했다.

이 재현을 기록할 때 확인한 XML 보고서의 시작 시각은 `2026-10-29T06:42:26.325Z`이며, KST로는 `2026-10-29 15:42:26.325`다. 사용자가 실행한 로컬 결과를 읽어 기록했으며, 문서 작성 과정에서 테스트를 다시 실행하지 않았다.

### 3.6 원인 분석

#### 코드로 확인한 구조

중복·충돌 확인은 SELECT이며, 검사 이후 정원 확보 UPDATE와 예약 INSERT가 실행된다. 여러 요청이 저장 전 검사를 통과할 여지가 있다.

아래는 이 구조에서 가능한 실행 순서를 단순화한 예시다. 각 요청의 상세 SQL·commit 순서를 추적해 복원한 결과는 아니다.

| 순서 | 요청 A | 요청 B |
| --- | --- | --- |
| 1 | 중복·시간 충돌 조회: 없음 | |
| 2 | | 중복·시간 충돌 조회: 없음 |
| 3 | 정원 확보 | |
| 4 | 예약 저장 및 commit | |
| 5 | | 정원 확보 |
| 6 | | 예약 저장 및 commit |

슬롯 정원 UPDATE가 DB에서 순서대로 처리되더라도, **이미 통과한 회원 중복·충돌 검사가 자동으로 다시 실행되지는 않는다.**

`@Transactional`은 한 요청의 정원 확보와 예약 저장을 함께 commit 또는 rollback하도록 묶는다. 현재 설정만으로 동일 회원의 여러 요청 전체가 순차 실행되지는 않는다.

#### 첫 번째 테스트 결과가 입증하는 것과 한계

- 입증: 같은 회원·같은 슬롯의 요청 10개가 모두 성공하고, commit된 활성 예약 10건이 조회됐다.
- 입증: 이번 실행에서 reservedCount는 실제 활성 예약 수와 일치했다.
- 미검증: 정원이 부족한 상황에서도 정원 초과가 항상 방지되는지 여부. 이번 테스트는 정원 20명에 요청 10개다.
- 이 테스트의 범위 밖: 서로 다른 슬롯의 시간 충돌, 중복 취소, 취소와 생성의 경합. 시간 충돌은 아래 두 번째 테스트에서 별도로 재현했다.
- 한계: 시작 시점 동기화는 각 SQL의 실행 순서까지 고정하지 않는다. 한 번 테스트가 통과하더라도 동시성 안전성을 입증하지는 못한다.

## 4. 해결 설계 시 유지할 조건

회원 행 잠금을 적용했으며, 아래 조건을 기준으로 설계와 후속 검증을 진행한다. 적용 내용과 확인한 결과는 6장에 기록한다.

- 동일 회원·동일 슬롯의 활성 예약 중복을 막는다.
- 동일 회원의 시간이 겹치는 다른 슬롯 예약도 막는다.
- 문서의 활성 예약 기준 DB 유니크 요구사항을 충족하는지 확인한다.
- 취소 이력을 남기면서 같은 슬롯의 새 예약을 허용한다.
- 실패한 요청의 정원 확보는 rollback되고, 실제 예약 수와 reservedCount가 일치한다.
- 서로 다른 회원의 정상 예약까지 불필요하게 직렬화하지 않는지 검토한다.

주의할 설계 사항:

- `(user_id, slot_id)` 전체 유니크만 적용하면 취소 이력이 새 예약을 막을 수 있다.
- 동일 슬롯에 대한 유니크만으로는 서로 다른 슬롯의 시간 충돌을 막을 수 없다.
- 회원 단위 동시성 제어와 활성 예약 DB 제약의 역할을 구분해 검토한다.

## 5. 재현 ② 동일 회원·서로 다른 슬롯의 시간 충돌

### 5.1 목적과 조건

서로 다른 어트랙션의 슬롯이라도 같은 회원의 탑승 시간이 겹치면 **두 요청 중 하나만 성공해야 한다**는 BR-RSV-04를 검증한다.

DB·회원·티켓·기준 시각과 transaction 구성은 첫 번째 테스트와 같다. 기존 슬롯에 더해 다른 어트랙션의 슬롯을 준비하고, 각 슬롯으로 요청 1개씩을 보낸다.

| 항목 | 설정 |
| --- | --- |
| 슬롯 A | 어트랙션 A, 16:00–16:30 |
| 슬롯 B | 어트랙션 B, 16:10–16:40 |
| 겹치는 구간 | 16:10–16:30 |
| 정원·초기 예약 인원 | 슬롯별 정원 20명, reservedCount 0명 |
| 요청 | 각 슬롯에 동시에 1개씩, 총 2개 |
| 기대하는 실패 사유 | `RESERVATION_TIME_CONFLICT` |
| 기록 시점 HEAD | `69552a4d7ad0f9c489a1fe5533f11e7c4d86b0d7` |
| 테스트 코드 상태 | 2차 재현 테스트 추가 변경은 미커밋 상태. HEAD만으로 추가된 테스트까지 복원할 수 없음 |

### 5.2 테스트 구성과 실행

기존 [ReservationConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationConcurrencyIntegrationTest.java)에 다음 메서드를 추가했다.

```java
createReservation_SameUserAndOverlappingSlotsConcurrently_OnlyOneSucceeds()
```

- 동시 실행 도우미가 서로 다른 요청 목록을 받아 요청 수만큼 스레드를 준비하도록 확장했다. 첫 번째 테스트의 요청 10개 조건은 유지했다.
- 각 요청은 실제 Service와 독립된 transaction을 사용한다.
- 두 요청이 모두 끝난 후 두 슬롯의 활성 예약 수와 각각의 reservedCount를 조회한다.
- 성공 1건, 활성 예약 1건, 예약 인원 합계 1명, 시간 충돌 실패 1건을 검증한다.
- 추가한 슬롯과 어트랙션은 테스트 종료 시 함께 정리한다.

Git Bash 실행 명령:

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest.createReservation_SameUserAndOverlappingSlotsConcurrently_OnlyOneSucceeds"
```

핵심 로그 검색:

```bash
grep -n "시간 충돌 동시 예약 결과" build/test-results/test/*ReservationConcurrency*.xml
```

### 5.3 기대 결과와 실제 결과

| 검증 항목 | 기대 | 실제 | 판정 |
| --- | ---: | ---: | --- |
| 성공한 요청 수 | 1 | 2 | 겹치는 요청 모두 성공 |
| 두 슬롯에 저장된 해당 회원의 RESERVED 예약 수 | 1 | 2 | 탑승 시간이 겹치는 예약 생성 |
| 슬롯 A reservedCount | 0 또는 1 | 1 | 자리 1개 확보 |
| 슬롯 B reservedCount | 0 또는 1 | 1 | 자리 1개 확보 |
| 두 슬롯의 reservedCount 합계 | 1 | 2 | 동일 회원이 겹치는 시간에 두 자리 확보 |
| 시간 충돌로 거절된 요청 수 | 1 | 0 | 시간 충돌 차단 실패 |
| 인원 합계와 실제 활성 예약 수 | 일치 | 2 = 2 | 이번 실행에서는 일치 |

실제 출력:

```text
시간 충돌 동시 예약 결과: 요청=2, 성공=2, 활성 예약=2, 슬롯A=1, 슬롯B=1, reservedCount합계=2, 실패=[]
```

이번 실행은 두 번째 테스트만 실행한 결과다.

```text
tests=1, skipped=0, failures=1, errors=0
```

실패한 assertion은 성공 요청 수, 활성 예약 수, 예약 인원 합계, 시간 충돌로 거절된 요청 수의 네 가지다. 실패 목록이 비어 있으므로 `RESERVATION_TIME_CONFLICT`가 실제로 반환되는 경로는 이번 실행에서 검증되지 않았다.

**판정: 동일 회원이 시간이 겹치는 서로 다른 슬롯을 모두 예약하는 문제를 실제 MySQL에서 재현했다.**

XML 보고서의 시작 시각은 `2026-10-29T10:37:30.909Z`, KST로는 `2026-10-29 19:37:30.909`다. 사용자가 실행한 결과를 읽어 기록했으며, 문서 갱신 과정에서 테스트를 다시 실행하지 않았다.

### 5.4 원인 분석과 첫 번째 테스트와의 차이

첫 번째 테스트는 같은 슬롯에 대한 요청이므로 정원 확보 UPDATE 대상 행도 같다. 그래도 중복 검사가 정원 확보 전에 이미 끝날 수 있어 회원 중복을 막지 못했다.

두 번째 테스트는 **정원 확보 대상 행부터 서로 다르다.** 슬롯별 정원 확보가 안전하게 처리되더라도, 같은 회원의 두 예약 시간을 비교하고 생성하는 전체 과정이 하나의 순서로 보장되지는 않는다.

이 결과는 단순히 같은 슬롯의 중복만 막아서는 충분하지 않다는 점을 보여준다. `(user_id, slot_id)` 조합이 서로 다르기 때문에, 해당 조합의 유니크 제약만으로는 두 번째 문제를 방어할 수 없다.

이는 코드 구조와 최종 DB 상태에 근거한 분석이다. 요청별 SELECT·UPDATE·INSERT·commit의 정확한 실행 순서를 추적한 기록은 아니다.

### 5.5 두 재현 결과 비교

| 구분 | 재현 ① 동일 슬롯 | 재현 ② 다른 슬롯 시간 충돌 |
| --- | --- | --- |
| 요청 수 | 10 | 2 |
| 실제 성공 수 | 10 | 2 |
| 실제 활성 예약 수 | 10 | 2 |
| 정원 UPDATE 대상 | 같은 슬롯 행 | 서로 다른 슬롯 행 |
| 위반한 회원 예약 규칙 | 동일 슬롯 중복 금지 | 활성 예약 간 시간 중복 금지 |
| 개선 후 확인 기준 | 성공 1건 | 성공 1건 및 시간 충돌 거절 1건 |

두 번째 재현 시점에는 취소 후 정상 재예약과 다른 회원의 동시 예약을 아직 검증하지 않았다. 이후 추가한 회귀 테스트 결과는 6.5절에 기록한다. 중복 취소와 취소·생성 경합은 여전히 미검증이며, 이번 결과로 동시성 문제 전반이 검증됐다고 판단하지 않는다.

## 6. 개선 적용 및 재검증 기록

### 6.1 선택한 방식: 회원 행의 PESSIMISTIC_WRITE

두 문제 모두 **같은 회원이 동시에 활성 예약을 생성한다**는 공통점이 있다. 중복·시간 충돌 검사 전에 해당 회원 행을 잠가, 같은 회원의 예약 생성 요청이 순서대로 진행되도록 했다.

| 검토 대상 | 이번 작업에서의 판단 |
| --- | --- |
| 기존 예약 행 잠금 | 최초 예약 시 조회되는 예약 행이 없을 수 있다. 존재하지 않는 구간의 잠금은 DB·isolation level에 따른 별도 설계가 필요하다. |
| 슬롯 행 잠금 | 같은 슬롯의 요청은 조정할 수 있지만, 서로 다른 슬롯의 시간 충돌을 함께 보호하기 어렵다. |
| 회원 행 잠금 | 예약 전에 존재하고 두 요청이 공유하는 행이다. 동일 회원의 예약 검사를 순서대로 진행하는 기준으로 선택했다. |
| 동일 회원·슬롯 DB 유니크 제약 | 중복 예약의 별도 방어 장치로 검토가 필요하다. 다른 슬롯의 시간 충돌까지 해결하지는 못한다. |

잠금 대상 비교는 코드 구조를 바탕으로 검토한 내용이다. 각 대안을 구현해 성능이나 처리량을 비교한 실험은 아직 수행하지 않았다.

회원 행을 잠근다고 관련 예약 행이 자동으로 모두 잠기는 것은 아니다. **예약 생성 경로가 같은 회원 잠금을 먼저 획득한다는 공통 절차**로 검증과 저장을 조정한다. 다른 회원은 다른 행을 잠그지만, 같은 슬롯을 선택하면 슬롯 정원 UPDATE에서는 경합할 수 있다.

### 6.2 적용한 코드와 transaction 범위

적용 commit: `58b29bd2965559435e26bd4e94327746869ec626`

| 파일 | 변경 또는 유지한 내용 |
| --- | --- |
| `UserRepository.java` | 기존 `findByIdForUpdate()` 재사용. `PESSIMISTIC_WRITE`로 회원 ID에 해당하는 행 조회. 탈퇴·예약의 순차 처리를 설명하도록 주석 보완 |
| `UserService.java` | `findByUserId()`에서 일반 `findById()` 대신 `findByIdForUpdate()` 호출 |
| `ReservationService.java` | 기존 `createReservation()`의 쓰기 transaction과 회원 조회 위치 유지. 동시성 TODO 주석 제거 |

`createReservation()`의 transaction 안에서 회원 잠금을 획득한다. 회원 조회가 끝났다고 잠금이 해제되는 것이 아니라, 예약 생성 transaction의 commit 또는 rollback 시점까지 유지된다.

```text
요청 A: 회원 잠금 획득 → 중복·충돌 검사 → 정원 확보 → 예약 저장 → commit·잠금 해제
요청 B: 같은 회원 잠금 대기 → 잠금 획득 → 앞선 예약을 반영한 검사 → 예약 거절
```

위 흐름은 적용한 제어 방식을 설명한 것이다. 요청별 대기 시간이나 모든 SQL 실행 순서를 별도로 계측하지는 않았다. 대기 후 조회가 앞선 예약을 확인하는지는 실제 DB의 transaction 설정과 함께 검증해야 한다.

### 6.3 개선 전후 비교

사용자는 회원 잠금 적용 후 **두 테스트 모두 통과**했다고 확인했다. 이 절을 최초 기록할 때 남아 있던 XML·HTML에는 시간 충돌 테스트 1개만 기록되어 있었다. 따라서 아래 표는 **직접 읽은 로그와 사용자 통과 보고를 구분**한다.

| 항목 | 개선 전 | 개선 후 | 개선 후 근거 |
| --- | --- | --- | --- |
| 동일 슬롯 요청 10개의 성공 수 | 10 | 1 | 사용자 통과 보고 및 테스트 기대값 |
| 동일 슬롯 활성 예약·reservedCount | 각각 10 | 각각 1 | 사용자 통과 보고 및 테스트 기대값 |
| 겹치는 다른 슬롯 요청 2개의 성공 수 | 2 | 1 | 최신 XML·출력 로그 직접 확인 |
| 겹치는 두 슬롯의 활성 예약 수 | 2 | 1 | 최신 XML·출력 로그 직접 확인 |
| 두 슬롯 reservedCount 합계 | 2 | 1 | 최신 XML·출력 로그 직접 확인 |
| 시간 충돌 실패 요청 | 0 | 1 | 최신 출력 로그와 통과한 ErrorCode assertion 확인 |

시간 충돌 테스트의 개선 후 실제 출력:

```text
시간 충돌 동시 예약 결과: 요청=2, 성공=1, 활성 예약=1, 슬롯A=0, 슬롯B=1, reservedCount합계=1, 실패=[com.example.attractionreservation.global.exception.BusinessException: 다른 예약과 탑승 시간이 겹칩니다.]
```

시간 충돌 테스트의 개선 후 기록 당시 XML 결과:

```text
tests=1, skipped=0, failures=0, errors=0
timestamp=2026-10-29T13:04:18.330Z
```

KST 기준 시작 시각은 `2026-10-29 22:04:18.330`이다. 이 timestamp는 보고서의 실제 실행 시각이며, Service에서 사용하는 테스트 기준 시각은 여전히 14:00 KST로 고정되어 있다.

이번 실행에서는 슬롯 B 예약이 성공하고 슬롯 A 예약이 거절됐다. 동시 요청 중 어느 슬롯이 성공하는지는 고정하지 않으며, **둘 중 하나만 성공하고 나머지는 시간 충돌로 거절되는지**가 검증 기준이다.

이 절을 최초 기록할 때는 동일 슬롯 테스트의 개선 후 로그 원문이 보고서에 남아 있지 않았다. 이후 전체 실행에서 확인한 실제 결과는 6.9절에 추가했다. 문서 갱신 과정에서 테스트를 다시 실행하지 않았다.

### 6.4 확인된 효과와 남은 검증

현재 재현 시나리오에서는 회원 단위 잠금 적용 후 중복·시간 충돌 방지의 기대값을 만족했다. 다만 두 테스트의 통과를 예약 동시성 전반의 해결이나 처리량 개선으로 확대 해석하지 않는다.

- **DB 제약:** 회원 잠금 이후 활성 예약 유니크 제약을 Entity에 추가하고 실제 MySQL 검증 테스트 4개를 통과했다. 기존 DB에 migration SQL을 적용하는 작업은 별도로 남아 있다.
- **정상 흐름 회귀:** 시간 미충돌 동시 예약, 취소 후 재예약, 다른 회원의 정상 동시 예약은 아래 6.5절의 조건에서 통과를 확인했다. 다른 회원의 정상 예약 테스트는 정원 20명에 요청 2개이므로 정원 부족 시 동작까지 검증하지는 않는다.
- **rollback:** 중복·시간 충돌 거절은 정원 확보 전에 발생한다. 이와 별도로 정원 확보 후 save 호출에 실패를 주입해 인원 복구를 확인했다. 실제 DB INSERT 실패·commit 실패를 주입한 테스트는 아니다.
- **다른 경합:** 중복 취소, 취소와 생성, 탈퇴와 생성의 동시 실행은 검증하지 않았다.
- **transaction 설정과 운영 DB:** 실제 session isolation level을 기록하고, 잠금 획득 전 조회 유무 등 향후 변경이 조회 결과에 미치는 영향을 검토해야 한다.
- **시간 경계:** 현재 코드는 잠금 획득 전에 `now`를 구한다. 잠금 대기가 예약 마감 시각을 넘는 경우의 시간 판단은 이번 테스트에서 검증하지 않았다.
- **성능:** 회원 행을 사용하는 다른 변경 작업과의 경합, 잠금 대기·timeout, 처리량은 아직 측정하지 않았다.

### 6.5 추가 회귀 테스트 결과

회원 잠금으로 잘못된 예약을 막는 것뿐 아니라, **허용되어야 하는 예약 흐름을 계속 허용하는지** 확인했다. 세 테스트 모두 사용자가 통과했다고 확인했다.

이 절을 최초 기록한 시점의 HEAD는 `93fcabcbe624b6fa12eb9f8926f7c64e24ea38a9`이며, 당시 테스트 파일에 미커밋 변경은 없었다. 아래는 그 시점의 개별 실행 기록이다. 이후 클래스 전체 및 예약 도메인 전체 통과 결과는 6.9절에 추가했다.

| 시나리오 | 조건 | 통과 시 확인한 값 | 근거 |
| --- | --- | --- | --- |
| 같은 회원의 시간 미충돌 동시 예약 | 16:00–16:30, 17:00–17:30에 요청 1개씩 | 성공 2개, 활성 예약 2건, 슬롯별 인원 1명, 예외 없음 | 사용자 통과 보고 및 테스트 assertion |
| 취소 후 같은 슬롯 재예약 | 예약 생성 → 사용자 취소 → 새 예약 생성 | 취소 직후 인원 0명, 최종 활성 예약 1건·인원 1명, 취소 이력 포함 총 2건 | 사용자 통과 보고 및 테스트 assertion |
| 서로 다른 회원의 동일 슬롯 동시 예약 | 각자의 IN_USE 티켓으로 정원 20명인 같은 슬롯에 요청 1개씩 | 성공 2개, 회원별 활성 예약 1건, 슬롯 인원 2명, 예외 없음 | 사용자 통과 보고 및 최신 XML·로그 직접 확인 |

#### A. 시간이 겹치지 않는 정상 예약

```java
createReservation_SameUserAndNonOverlappingSlotsConcurrently_BothSucceed()
```

같은 회원 행 잠금으로 두 요청을 순서대로 처리하더라도, 시간이 겹치지 않으면 두 요청 모두 허용되는지 확인한다. 회원의 복수 활성 예약을 불필요하게 금지하지 않는다는 회귀 검증이다.

#### B. 취소 이력을 유지한 재예약

```java
createReservation_AfterCancellationSameSlot_CreatesNewReservation()
```

각 Service 호출이 별도 transaction으로 commit된 다음 단계를 실행하는 **순차 흐름**이다. 취소와 생성의 동시 경합 테스트는 아니다.

통과한 assertion은 기존 예약의 `CANCELLED` 상태·취소 시각 보존, 새 ID·새 예약 번호의 `RESERVED` 예약 생성, 새 예약의 취소 시각 없음, 총 이력 2건과 활성 예약 1건을 확인한다. 향후 활성 예약 유니크 제약을 적용해도 취소 이력이 새 예약을 막지 않아야 한다는 회귀 기준으로 유지한다.

A·B 테스트의 출력 원문은 이 절을 최초 기록할 때 남아 있지 않아 사용자 통과 보고로 기록했다. 이후 전체 실행의 XML에서 직접 확인한 실제 출력은 6.9절에 추가했다.

#### C. 다른 회원의 정상 동시 예약

```java
createReservation_DifferentUsersAndSameSlotConcurrently_BothSucceed()
```

별도 ACTIVE 회원과 해당 회원 소유의 IN_USE 티켓을 준비하고, 두 회원이 같은 슬롯을 예약한다. 도우미가 요청별 회원 ID와 요청 DTO를 받아 독립된 transaction으로 실제 Service를 호출한다.

같은 슬롯의 정원 UPDATE는 경합할 수 있지만, 정원이 충분하면 두 요청 모두 정상적으로 완료돼야 한다. 회원별 활성 예약이 정확히 1건씩인지도 DB에서 확인한다.

최신 XML에서 직접 확인한 출력:

```text
다른 회원 동시 예약 결과: 요청=2, 성공=2, 활성 예약=2, 예약회원=[2, 1], reservedCount=2, 실패=[]
```

```text
tests=1, skipped=0, failures=0, errors=0
timestamp=2026-10-29T14:17:26.586Z
```

KST 시작 시각은 `2026-10-29 23:17:26.586`이다. 이 실행에서도 Service의 테스트 기준 시각은 14:00 KST로 고정되어 있다. 출력의 회원 ID와 순서는 해당 실행의 값이며, 특정 회원이 먼저 성공해야 한다는 조건은 없다.

이 테스트의 통과는 다른 회원의 정상 예약을 허용한다는 근거다. 서로 다른 회원이 전혀 대기하지 않거나 병렬 성능이 개선됐다는 의미는 아니며, 잠금 대기 시간은 별도로 측정하지 않았다.

#### 다시 실행할 때

세 테스트를 포함한 클래스 전체 실행 명령은 다음과 같다. 문서 갱신 과정에서는 실행하지 않았다.

```bash
./gradlew test --tests "*ReservationConcurrencyIntegrationTest" --rerun-tasks
```

### 6.6 정원 확보 후 저장 실패 시 rollback

테스트 메서드:

```java
createReservation_SaveFailsAfterCapacityAcquired_RollsBackCapacity()
```

실제 Service와 MySQL의 정원 확보 UPDATE를 사용한다. `ReservationRepository`는 Spy로 두고 이 테스트의 `save()` 호출에만 예외를 주입한다.

1. 저장 직전 같은 transaction에서 DB의 reservedCount를 조회한다.
2. 예약 저장 호출에서 의도적으로 `IllegalStateException`을 발생시킨다.
3. Service transaction 종료 후 새로운 transaction에서 DB 상태를 조회한다.
4. 실패 직전 인원 1명, rollback 후 인원 0명·잔여 정원 20명·남은 예약 0건을 검증한다.

최신 XML의 실제 출력:

```text
예약 저장 실패 롤백 결과: 실패직전인원=1, 롤백후인원=0, 남은예약=0
```

**판정:** 정원 확보 UPDATE와 후속 저장 호출이 동일한 예약 생성 transaction에 묶여, 후속 RuntimeException 발생 시 정원 증가도 rollback됐다.

**범위:** 순차적인 실패 복구 테스트다. 실제 INSERT의 DB 제약 위반이나 연결 단절·commit 실패를 재현한 것은 아니다. Spy 추가 후 클래스의 나머지 테스트가 함께 통과하는지도 확인했다.

### 6.7 활성 예약 DB Unique Constraint

#### 회원 잠금에 더해 DB 제약을 추가한 이유

회원 잠금은 같은 회원의 요청이 중복·시간 충돌 검사를 순서대로 수행하도록 한다. DB 제약은 이 잠금과 Service 검사를 거치지 않는 저장 경로에서도 **동일 회원·슬롯의 활성 예약 중복 저장 자체를 거절**하는 역할을 맡는다.

이는 BR-RSV-03·31의 활성 예약 기준 유니크 요구사항을 DB 수준에서 구현하기 위한 보완이다. 서로 다른 슬롯의 시간 충돌은 이 제약만으로 막을 수 없으므로 회원 잠금과 충돌 검사를 유지한다.

#### Generated Column과 NULL을 사용한 설계

Entity에 `active_reservation`을 읽기 전용 `Integer`로 매핑했다. INSERT·UPDATE 값은 JPA가 직접 쓰지 않고 DB가 아래 식으로 계산한다.

```sql
CASE WHEN reservation_status = 'RESERVED' THEN 1 ELSE NULL END
```

유니크 제약 이름과 컬럼 조합:

```text
uk_reservations_user_slot_active
(user_id, attraction_slot_id, active_reservation)
```

| 예약 상태 | 보조 값 | 같은 회원·슬롯에서의 의미 |
| --- | --- | --- |
| RESERVED | 1 | 같은 조합의 활성 예약은 1건만 허용 |
| 나머지 상태 | NULL | MySQL 유니크 키의 NULL 허용 특성으로 과거 이력 여러 건 허용 |

비활성 값을 0으로 두면 `(회원, 슬롯, 0)`도 중복으로 판정되어 두 번째 취소 이력이 막힌다. `(회원, 슬롯, 상태)` 조합의 단순 유니크도 여러 CANCELLED 이력을 막기 때문에 사용하지 않았다.

DB 계산값이 JPA 필드에 즉시 반영되도록 하는 별도 설정은 추가하지 않았다. 테스트에서는 `flush()` 후 `refresh()`로 실제 계산된 값을 읽어 확인한다.

#### DB 제약 자체를 검증하는 테스트 4개

파일: [ReservationUniqueConstraintIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/repository/ReservationUniqueConstraintIntegrationTest.java)

Service·회원 잠금을 사용하지 않고 실제 MySQL에 Repository로 직접 저장한다. 따라서 회원 잠금에 가려지지 않은 DB 제약 자체를 확인할 수 있다.

| 테스트 메서드 | 확인한 동작 |
| --- | --- |
| `saveAndFlush_SameUserAndSlotReservedTwice_ThrowsUniqueConstraintViolation()` | 첫 활성 예약의 보조 값 1, 두 번째 저장은 해당 제약 이름을 포함한 DataIntegrityViolationException 발생 |
| `saveAndFlush_RepeatedCancellationAndRebooking_PreservesHistoryAndOneActiveReservation()` | 두 번 취소 후 새 예약 허용, 상태 CANCELLED·CANCELLED·RESERVED 및 보조 값 NULL·NULL·1 |
| `saveAndFlush_DifferentUsersSameSlot_AllowsBothActiveReservations()` | 다른 회원은 같은 슬롯에 활성 예약을 각각 저장 가능 |
| `saveAndFlush_SameUserDifferentSlots_AllowsBothActiveReservations()` | 같은 회원은 다른 슬롯에 활성 예약을 각각 저장 가능 |

실제 로그:

```text
활성 예약 DB 제약 결과: 첫 예약 active=1, 중복 저장 거절, 제약=uk_reservations_user_slot_active
반복 취소 후 DB 제약 결과: 전체=3, 상태=[CANCELLED, CANCELLED, RESERVED], 보조값=[null, null, 1]
```

최신 XML: `tests=4, skipped=0, failures=0, errors=0`.

이 테스트들은 스키마 제약을 검증하므로 Service의 정원 증가 처리를 실행하지 않는다. 이 테스트의 예약 건수로 슬롯 reservedCount 정합성까지 판단하지 않는다. COMPLETED·NO_SHOW 각각의 계산값이나 회원 잠금 없이 직접 저장하는 동시 INSERT 경합은 별도 테스트하지 않았다.

### 6.8 migration SQL 준비와 실제 DB 적용 상태

파일: [kan-87-reservation-active-unique-migration.sql](../sql/kan-87-reservation-active-unique-migration.sql)

일반 실행 설정은 `ddl-auto: validate`이므로 Entity 변경만으로 기존 DB에 컬럼과 제약이 추가되지 않는다. 기존 `reservations` 테이블에 변경사항을 적용할 SQL을 별도로 작성했다.

| 순서 | SQL의 역할 |
| --- | --- |
| 1 | 대상 DB와 기존 테이블 구조 확인 |
| 2 | 동일 회원·슬롯의 RESERVED 중복 조회 |
| 3 | Generated Column과 유니크 제약을 하나의 ALTER TABLE로 추가 |
| 4 | SHOW CREATE TABLE·SHOW INDEX로 계산식과 제약 확인 |
| 5 | 상태별 보조 값 계산 결과 조회 |

사용자가 연결한 로컬 `attraction_reservation` DB에서 중복 조회를 시도했으나 `Table 'attraction_reservation.reservations' doesn't exist` 오류가 발생했다. 이후 사용자가 테이블 목록이 비어 있다고 확인했다. 이는 **중복 데이터 0건을 조회한 결과가 아니라 테이블 자체가 없는 상태**다.

따라서 이 DB에 migration을 실행하지 않았다. 스크립트는 기존 테이블이 있고 대상 컬럼·제약이 없는 스키마용이며, 빈 DB의 전체 테이블을 초기화하는 SQL이 아니다.

**검증 구분:** 통과한 DB 테스트는 Testcontainers의 `create-drop` 설정으로 Entity에서 생성한 스키마를 검증했다. 작성한 migration SQL을 변경 전 스키마에 실행한 검증이나 실제 운영 DB 적용은 수행하지 않았다.

스크립트는 단계별 확인 후 실행하도록 작성했다. 사전 SELECT가 중복을 반환해도 이후 문장을 자동 중단하지는 않는다. 실제 적용 시 예약 쓰기 중지·백업·중복 처리 판단과 적용 순서를 함께 관리해야 한다.

### 6.9 최종 테스트 결과와 검증 근거

사용자가 다음 순서로 실행하고 통과를 확인했다.

```bash
# 동시성·회귀·rollback 6개 + DB 제약 4개
./gradlew test --tests "*ReservationConcurrencyIntegrationTest" --tests "*ReservationUniqueConstraintIntegrationTest" --rerun-tasks

# 예약 도메인 전체
./gradlew test --tests "com.example.attractionreservation.domain.reservation.*" --rerun-tasks
```

문서 갱신 시점에 `build/test-results/test/`의 XML을 읽어 예약 도메인 suite를 집계했다.

| 범위 | 클래스 수 | 테스트 수 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 예약 도메인 전체 | 21 | 175 | 0 | 0 | 0 |
| 위 전체 실행에 포함된 동시성·회귀·rollback 클래스 | 1 | 6 | 0 | 0 | 0 |
| 위 전체 실행에 포함된 DB 제약 클래스 | 1 | 4 | 0 | 0 | 0 |

아래 두 클래스의 10개는 175개에 포함된 값이며 별도로 더하지 않는다. 프로젝트 전체 테스트 실행 결과는 아니다.

같은 최신 보고서에서 직접 확인한 주요 값:

| 검증 | 실제 로그로 확인한 결과 |
| --- | --- |
| 동일 회원·동일 슬롯 요청 10개 | 성공 1, 활성 예약 1, reservedCount 1, 나머지 9개는 중복 예약 예외 |
| 동일 회원·겹치는 다른 슬롯 요청 2개 | 성공 1, 활성 예약 1, 슬롯 A 1·B 0, 나머지 1개는 시간 충돌 예외 |
| 동일 회원·겹치지 않는 다른 슬롯 요청 2개 | 성공 2, 활성 예약 2, 슬롯별 인원 1 |
| 취소 후 재예약 | 기존 예약 3 CANCELLED, 새 예약 4 RESERVED, 총 이력 2, 취소 직후 인원 0·최종 1 |
| 다른 회원·동일 슬롯 요청 2개 | 성공 2, 활성 예약 2, 예약 인원 2 |
| 정원 확보 후 save 실패 | 실패 직전 인원 1, rollback 후 인원 0, 남은 예약 0 |

동일 슬롯 테스트는 9개의 중복 예약 예외를 출력했다. 예외 목록을 줄인 요약은 다음과 같다.

```text
요청=10, 성공=1, 활성 예약=1, reservedCount=1
실패 요청 9개: BusinessException / 이미 예약한 슬롯입니다.
```

회귀 테스트 원문 출력도 확보했다.

```text
취소 후 재예약 결과: 기존예약=3(CANCELLED), 새예약=4(RESERVED), 전체이력=2, 활성예약=1, 취소직후인원=0, 최종인원=1
시간 미충돌 동시 예약 결과: 요청=2, 성공=2, 활성 예약=2, 슬롯A=1, 슬롯B=1, reservedCount합계=2, 실패=[]
```

기존 절에서 사용자 통과 보고로만 기록했던 항목도 이번 최신 XML에서 확인했다. 이전 개별 실행 기록은 보존하고 이번 결과를 추가했다. 예약 ID와 승리한 슬롯은 실행에 따라 달라질 수 있으며 검증의 고정값이 아니다.

기록 시점 HEAD는 `f425373c6bd4b1cb7e03ee22cdd08cda8d01fac6`이다. Generated Column·제약의 Entity 변경과 SQL, 새 DB 제약 테스트는 아직 미커밋 상태이므로 이 HEAD만으로 최종 검증 코드를 복원할 수 없다.

최신 DB 제약 suite 시작 시각은 `2026-10-29T15:59:13.560Z`(KST 2026-10-30 00:59:13.560), 동시성 suite 시작 시각은 `2026-10-29T15:59:31.980Z`(KST 2026-10-30 00:59:31.980)다. 문서 작성 과정에서는 테스트를 다시 실행하지 않고 사용자가 실행한 XML을 읽었다.

### 6.10 완료 체크리스트

- [x] 동일 회원·동일 슬롯 동시 요청 재현
- [x] 개선 전 성공 건수·활성 예약 수·reservedCount 기록
- [x] 서로 다른 슬롯의 시간 충돌 재현
- [x] 해결 방식 비교 및 선택 근거 기록
- [x] 회원 행 비관적 잠금 적용
- [x] 활성 예약 DB 유니크 제약 매핑 및 실제 MySQL 검증
- [x] 동일 슬롯 동시 요청에서 성공 1건 검증 — 최신 XML·로그 확인
- [x] 동일 슬롯 테스트의 개선 후 성공 수 및 실패 사유를 문서에 보관
- [x] 시간이 겹치는 다른 슬롯 동시 요청에서 성공 1건 검증 — XML·로그 확인
- [x] 정원 확보 후 save 호출 실패 주입 시 rollback 검증
- [x] 같은 회원의 시간 미충돌 동시 예약 성공 검증 — 최신 XML·로그 확인
- [x] 취소 후 같은 슬롯에 새 예약 생성 가능 여부 검증 — 최신 XML·로그 확인
- [x] 서로 다른 회원의 정상 예약 회귀 검증 — XML·로그 확인
- [x] 직접 저장 시 중복 활성 예약 거절 및 반복 취소 이력 허용 검증
- [x] 동시성·회귀·rollback 6개와 DB 제약 4개 통과 확인
- [x] 기존 예약 도메인 테스트 결과 기록 — 21개 클래스, 175개 테스트 통과
- [x] 기존 DB에 적용할 migration SQL 작성
- [ ] 변경 전 스키마에 migration SQL을 실행해 적용 검증
- [ ] 실제 적용 대상 DB의 사전 데이터 확인 및 migration 반영
- [x] 현재 확인한 개선 전후 결과와 검증 한계 기록

## 7. 증거 보관과 추가 기록 방법

`build/` 아래 보고서는 다음 실행이나 clean으로 바뀔 수 있다. 이 문서에는 핵심 수치를 복사해 두었으며, XML·HTML 전체 파일이나 화면 이미지는 별도로 보관하지 않았다.

추가 실험은 기존 결과를 덮어쓰지 않고 다음 항목으로 이어서 기록한다.

```markdown
### 실험 제목 · YYYY-MM-DD
- 목적:
- 브랜치·commit 및 미커밋 변경:
- 환경·DB isolation level:
- 테스트 조건과 실행 명령:
- 기대 결과:
- 실제 로그 및 DB 상태:
- 판정과 분석:
- 다음 검증:
```
