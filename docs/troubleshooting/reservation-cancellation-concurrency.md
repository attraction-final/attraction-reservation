# 예약 중복 취소와 상태 변경 경합 트러블슈팅

> **KAN-91 · 재현 및 개선 후 관련 테스트 통과**
>
> 같은 예약의 동시 취소로 슬롯 인원이 두 번 감소하는 문제를 재현했다. 사용자·관리자·완료·노쇼 변경에 예약 행 잠금을 적용하고, 탈퇴 경합에서 발생한 인덱스 잠금 deadlock을 진단해 탈퇴의 잠금 조회 방식을 변경했다. 최신 로컬 XML에서 취소 경합 6개와 관련 회귀 테스트를 포함한 237개 테스트의 통과를 확인했다.

## 1. 결과 먼저 보기

| 상황 | 개선 과정에서 확인한 문제 | 개선 후 확인 결과 |
| --- | --- | --- |
| 같은 예약에 사용자 취소 2개 | 둘 다 성공, 다른 예약이 남았는데 인원 0명 | 성공 1개·상태에 따른 거절 1개, 인원 1명 |
| 사용자 취소 ↔ 관리자 강제 취소 | 사용자만 잠금 적용한 단계에서 deadlock 발생 | 성공 1개·상태에 따른 거절 1개, 인원 1명 |
| 관리자 취소 ↔ COMPLETED | 둘 다 성공, 완료 상태에 취소의 인원 복구가 반영됨 | 상태 전이 1개만 성공, 상태와 인원 처리 일치 |
| 관리자 취소 ↔ NO_SHOW | 둘 다 성공, 노쇼 상태에 취소의 인원 복구가 반영됨 | 상태 전이 1개만 성공, 상태와 인원 처리 일치 |
| 탈퇴 ↔ 사용자 취소 | 해당 재현 실행에서는 정상 동작 | 탈퇴 완료, 예약 A만 취소, 인원 1명 |
| 탈퇴 ↔ 관리자 취소 | PRIMARY와 활성 예약 유니크 인덱스 사이 deadlock | 탈퇴 완료, 예약 A만 취소, 인원 1명 |

이 문서의 결과는 아래 테스트 조건에서 확인한 내용이다. 모든 실행 순서에서 deadlock이 절대 발생하지 않거나 전체 서비스의 동시성이 해결됐다는 의미는 아니다.

관련 선행 작업: [KAN-87 예약 생성 동시성 기록](reservation-concurrency.md)

## 2. 문제와 요구사항

### 예약 생성 중복과 취소 중복은 별개의 문제

KAN-87에서는 회원 행 잠금과 활성 예약 DB 유니크 제약으로 예약 생성의 중복·시간 충돌을 방어했다. 이 장치가 같은 예약의 취소를 한 번만 처리하도록 보장하지는 않는다.

기존 취소 흐름은 다음과 같았다.

```text
예약 일반 조회 → RESERVED 상태 확인 → CANCELLED로 변경 → 슬롯 reservedCount 감소
```

두 transaction이 모두 RESERVED를 읽으면 각각 취소를 진행할 수 있다. 슬롯 감소 UPDATE에 `reservedCount > 0`이 있어도, 인원이 2명 이상이면 두 번 모두 감소 조건을 만족할 수 있다.

### 왜 초기 예약 인원을 2명으로 준비했나

서로 다른 회원의 예약 A·B가 같은 슬롯에 존재한다고 가정한다.

```text
초기: A=RESERVED, B=RESERVED, reservedCount=2
A에 대한 취소 요청 1: 2 → 1
A에 대한 취소 요청 2: 1 → 0
최종: B는 RESERVED인데 reservedCount=0
```

초기 인원이 1명이면 두 번째 감소가 `reservedCount > 0`에 막혀 rollback될 수 있어, 같은 예약의 중복 복구 문제를 놓치기 쉽다.

### 관련 비즈니스 규칙

출처는 읽기 전용 원본 참고 자료 `sources/비즈니스 규칙.md`이며, 원본 문서는 변경하지 않았다.

| 규칙 | 확인한 요구사항 |
| --- | --- |
| BR-RSV-10 | 본인의 예약 완료 상태 예약만 취소 가능 |
| BR-RSV-11 | 사용자 취소는 슬롯 시작 30분 전까지 가능 |
| BR-RSV-12 | 취소 상태 변경과 슬롯 정원 복구를 같은 transaction에서 처리 |
| BR-RSV-21 | 완료·노쇼는 슬롯 종료 이후에 처리 |
| BR-RSV-22 | 관리자는 일반 취소 시간 제한과 관계없이 강제 취소 가능 |
| BR-RSV-32 | 슬롯 정원과 예약 건수의 정합성 유지 |

관리자 완료·노쇼는 기존 구현에서 인원을 감소시키지 않는다. 해당 경합 테스트도 이 동작을 기준으로 취소 성공 시 인원 1명, 완료·노쇼 성공 시 인원 2명을 기대한다. 문서의 정원 산식에서 완료·노쇼를 어떤 집계 대상으로 볼지는 별도 정책 확인 사항이며, 이번 작업에서 새 정원 정책을 확정하거나 변경하지 않았다.

## 3. 재현 환경과 테스트 구성

파일: [ReservationCancellationConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationCancellationConcurrencyIntegrationTest.java)

| 항목 | 조건 |
| --- | --- |
| 브랜치 | `fix/KAN-91-reservation-cancellation-concurrency` |
| DB | Testcontainers MySQL, 이미지 태그 `mysql:9.6` |
| 스키마 | 테스트 프로필의 `create-drop`으로 Entity에서 생성 |
| Connection pool | 최대 10개 |
| 회원·티켓 | 서로 다른 ACTIVE 회원 2명과 각자 소유한 IN_USE 티켓 |
| 슬롯 | 2026-10-30 16:00–16:30, 정원 20명 |
| 예약 | 실제 예약 생성 Service로 A·B 생성, 초기 reservedCount=2 |
| 사용자 취소·탈퇴 시각 | 2026-10-30 14:00 KST로 고정 |
| 관리자 취소·완료·노쇼 경합 시각 | 2026-10-30 17:00 KST로 고정 |
| 동시 실행 | 스레드 2개, CountDownLatch로 작업 시작 시점 동기화 |
| transaction | 작업별 독립된 실제 Service transaction |
| 결과 확인 | 모든 작업 종료 후 새 transaction에서 DB 조회 |
| DB isolation level | 별도로 지정하지 않았으며 실제 session 값은 수집하지 않음 |

준비 데이터는 작업 스레드가 조회할 수 있도록 먼저 commit한다. 테스트 자체의 transaction은 `Propagation.NOT_SUPPORTED`로 비활성화한다. 작업에서 commit한 데이터는 테스트 종료 시 해당 fixture 범위만 정리한다.

`PasswordEncoder`와 `RefreshTokenRepository`는 UserService 생성에 필요한 mock이다. 예약 조회·상태 변경·슬롯 인원 복구·회원 탈퇴는 실제 Service와 Repository를 사용한다. Security 또는 HTTP 응답을 통합 검증한 테스트는 아니며, 성공·실패는 Service 반환 DTO와 예외를 기준으로 확인했다.

시간 관련 static mock은 스레드별로 적용한다. `KstTime.now()`와 `Instant.now()`를 사용하는 경로를 구분해, 시각 조건 때문에 경합 검증이 중단되지 않도록 했다.

## 4. 첫 재현: 동일 예약의 중복 취소

테스트 메서드:

```java
cancelReservation_SameReservationConcurrentlyWithTwoReserved_OnlyOneReleaseSucceeds()
```

예약 A의 소유자가 A에 사용자 취소 요청 2개를 동시에 보냈다.

| 항목 | 기대 | 개선 전 실제 |
| --- | ---: | ---: |
| 취소 성공 응답 | 1 | 2 |
| 실패 요청 | 1 | 0 |
| 남은 RESERVED 예약 | 1 | 1 |
| 슬롯 reservedCount | 1 | 0 |

당시 확인한 로그의 핵심 값:

```text
동일 예약 중복 취소 결과: 요청=2, 성공=2, 실패=0,
예약A=CANCELLED, 예약B=RESERVED, 활성예약=1, reservedCount=0
```

두 성공 DTO 모두 같은 예약 ID를 반환했다. 예약 A만 취소됐지만 슬롯 인원이 두 번 감소하여, 남아 있는 B와 인원 값이 어긋났다.

**원인:** 상태 검사와 변경을 요청 사이에서 직렬화하지 않았다. Entity의 상태 검사도 각 transaction이 이미 읽은 RESERVED 값을 기준으로 실행될 수 있다. `@Transactional`은 한 요청의 변경을 묶지만, 이 검사를 자동으로 한 요청씩 실행하지는 않는다.

**첫 변경:** 사용자 취소의 첫 조회를 예약 ID·회원 ID 조건의 `PESSIMISTIC_WRITE` 조회로 변경했다. 일반 상세 조회는 기존 방식으로 유지했다.

## 5. 변경 경로를 함께 보호해야 했던 이유

### 5.1 사용자 취소만 잠갔을 때의 관리자 경합

테스트 메서드:

```java
cancelReservation_UserAndAdminConcurrently_OnlyOneReleaseSucceeds()
```

사용자 취소에만 잠금을 적용하고 관리자 취소가 일반 조회를 사용하던 단계에서, 관리자 취소는 성공하고 사용자 요청은 `CannotAcquireLockException`으로 실패했다.

```text
성공=1, 실패=1
예약A=CANCELLED, 예약B=RESERVED, 활성예약=1, reservedCount=1
사용자: Deadlock found when trying to get lock
관리자: 취소 성공
```

최종 인원만 보면 정상처럼 보이지만, 실패 요청이 상태 검사에 따른 BusinessException 대신 DB deadlock 오류를 받았다. 테스트는 이를 정상적인 중복 요청 거절로 인정하지 않았다.

당시에는 상세 InnoDB 기록을 확보하지 않아 정확한 잠금 순환을 확정하지 못했다. 예약 잠금과 슬롯 잠금을 반대 순서로 얻는 경로를 원인 후보로 설명했으며, 이 추정을 다음 절의 확정 진단과 혼동하지 않는다.

**변경:** 관리자 강제 취소도 예약 ID로 잠금 조회한 뒤 상태를 검사하도록 연결했다. 재실행에서 성공 1개·상태 거절 1개·인원 1명을 확인했다.

### 5.2 취소와 완료·노쇼가 모두 성공한 사례

Parameterized Test 메서드:

```java
updateReservationStatus_AdminCancelAndTerminalStatusConcurrently_OnlyOneTransitionSucceeds()
```

사용자 취소는 완료·노쇼와 정상적인 실행 가능 시간대가 겹치지 않으므로, 시간 제한이 없는 관리자 강제 취소로 경합을 만들었다.

| 개선 전 결과 | COMPLETED 경합 | NO_SHOW 경합 |
| --- | --- | --- |
| 성공 응답 | 취소·상태 변경 모두 성공 | 취소·상태 변경 모두 성공 |
| A 최종 상태 | COMPLETED | NO_SHOW |
| 슬롯 인원 | 1 | 1 |
| 최종 취소 사유 | NULL | NULL |

관리자 취소가 성공 응답과 인원 복구를 남겼는데, 최종 예약은 완료·노쇼 상태였다. 하나의 상태 전이만 성공했을 때의 일관된 결과가 아니다.

**변경:** `updateReservationStatus()`도 관리자 취소와 동일한 `findByIdWithLock()`을 사용하도록 연결했다. 재실행에서 각 경우 모두 한 요청만 성공하고, 거절 예외와 최종 인원이 성공한 전이에 맞는지 확인했다.

## 6. 탈퇴 경합의 deadlock 진단

### 6.1 일반 취소와 달리 성공 요청이 두 개일 수도 있다

Parameterized Test 메서드:

```java
withdraw_UserOrAdminCancelConcurrently_ReleasesCapacityOnce()
```

USER·ADMIN 각각을 탈퇴와 경합시킨다. 기존 fixture의 티켓은 IN_USE이므로 미사용 티켓에 따른 탈퇴 거절은 발생하지 않는다.

일반·관리자 취소가 먼저 A를 취소하면, 탈퇴는 A를 다시 취소하지 않고 회원 상태만 변경하여 정상 완료할 수 있다. 이 경우 **두 요청 모두 성공하더라도 정원 복구는 한 번**이다.

반대로 탈퇴가 먼저 A를 취소하면, 일반·관리자 취소는 CANCELLED 상태를 확인하고 거절되어야 한다. 따라서 이 테스트는 성공 수를 무조건 1개로 제한하지 않고 다음을 검증한다.

- 탈퇴가 정상 완료되고 회원은 WITHDRAWN이다.
- A는 CANCELLED, B는 RESERVED다.
- 실제 RESERVED 예약 1건과 reservedCount=1이 일치한다.
- 먼저 성공한 취소의 사유가 유지된다.
- 별도 취소 요청이 실패했다면 DB 잠금 오류가 아닌 상태에 따른 BusinessException이다.

### 6.2 예약 잠금 적용 후에도 탈퇴·관리자 경합에서 실패

당시 실행은 USER 경합이 통과하고 ADMIN 경합만 실패했다.

```text
취소주체=ADMIN, 성공=1, 회원상태=WITHDRAWN
예약A=CANCELLED, 예약B=RESERVED, 활성예약=1, reservedCount=1
취소사유=USER_WITHDRAWAL
관리자 실패: CannotAcquireLockException / Deadlock found when trying to get lock
```

이미 관리자도 예약 잠금을 사용하는 단계였다. 단순히 잠금 annotation을 더 추가하기보다, 실제 DB가 어떤 잠금들을 기다렸는지 확인하기로 했다.

### 6.3 Testcontainers 종료 전에 진단 기록 확보

이 테스트 클래스의 MySQL에만 다음 시작 옵션을 추가했다.

```text
--innodb-print-all-deadlocks=ON
--log-error-verbosity=3
```

예상하지 않은 예외가 발생하면 `mysqlContainer.getLogs()`로 현재 테스트 시작 이후의 서버 로그를 출력한다. 테스트 종료 후 DB가 사라져도 XML의 Standard output에서 진단을 읽을 수 있도록 했다. 성공 기준은 완화하지 않았다.

```text
=== KAN-91 MySQL deadlock 진단 시작: WITHDRAWAL / ADMIN ===
...
=== KAN-91 MySQL deadlock 진단 끝 ===
```

### 6.4 확인된 원인: 같은 예약의 두 인덱스 잠금 순서 충돌

진단 실행에서 MySQL이 기록한 transaction은 다음과 같았다.

| 요청 | transaction | 이미 보유한 잠금 | 기다리는 잠금 |
| --- | --- | --- | --- |
| 탈퇴 | 2749 | `uk_reservations_user_slot_active`의 X 잠금 | 같은 예약의 PRIMARY X 잠금 |
| 관리자 취소 | 2750 | 해당 예약의 PRIMARY X 잠금 | 같은 유니크 인덱스 레코드의 X 잠금 |

탈퇴 transaction은 회원의 이용 예정 예약들을 `FOR UPDATE`로 조회하는 SQL을 실행 중이었다. 관리자 transaction은 해당 예약을 CANCELLED로 UPDATE하는 중이었다.

진단 기록에서 확인한 부분을 추린 발췌다. 줄 사이의 레코드 상세·시간·접속 정보 등은 생략했다.

```text
(1) TRANSACTION: 2749
HOLDS THE LOCK(S):
  index uk_reservations_user_slot_active of table test.reservations
WAITING FOR THIS LOCK TO BE GRANTED:
  index PRIMARY of table test.reservations

(2) TRANSACTION: 2750
HOLDS THE LOCK(S):
  index PRIMARY of table test.reservations
WAITING FOR THIS LOCK TO BE GRANTED:
  index uk_reservations_user_slot_active of table test.reservations

WE ROLL BACK TRANSACTION (2)
```

같은 예약을 다루더라도 목록 잠금 조회와 PK 단건 잠금 조회의 내부 인덱스 접근 순서가 달랐다. 또한 RESERVED에서 CANCELLED로 바뀌면 Generated Column 값도 1에서 NULL로 바뀌므로 유니크 인덱스 변경이 필요하다.

```text
탈퇴: 유니크 인덱스 잠금 보유 → PRIMARY 잠금 대기
관리자: PRIMARY 잠금 보유 → 유니크 인덱스 잠금 대기
```

MySQL은 이 순환을 감지해 관리자 transaction을 rollback했다. 이 원인은 추정이 아니라, 해당 실행의 InnoDB 진단에 보유·대기 인덱스와 rollback 대상이 함께 기록된 내용이다. 앞선 사용자·관리자 경합의 deadlock도 동일 원인이었다고 소급해 단정하지 않는다.

## 7. 적용한 해결 방식

### 7.1 사용자·관리자 변경 경로의 예약 잠금

| 경로 | 잠금 조회 | 유지한 검증 |
| --- | --- | --- |
| 사용자 취소 | `findByIdAndUserIdWithLock()` | 본인 예약, RESERVED, 사용자 취소 마감 |
| 관리자 강제 취소 | `findByIdWithLock()` | 예약 존재, RESERVED, 취소 사유 |
| 관리자 완료·노쇼 | `findByIdWithLock()` | 예약 존재, 슬롯 종료 이후, 현재·목표 상태 |

일반 사용자·관리자 상세 조회는 잠금 없는 조회를 유지했다. 변경용 조회부터 상태 검사·변경·필요한 인원 복구까지 기존 쓰기 transaction 안에서 실행한다.

### 7.2 탈퇴의 후보 조회와 잠금 획득 분리

기존의 `findUpcomingForUpdate()` 한 번으로 목록을 잠그는 호출을 다음 단계로 변경했다.

```text
회원 행 잠금 및 기존 탈퇴 가능 조건 확인
    ↓
이용 예정 예약 후보 ID를 일반 조회 — 예약 ID 오름차순
    ↓
각 ID를 findByIdWithLock()으로 단건 잠금 조회
    ↓
현재 RESERVED 및 startsAt > 기준 시각 재검사
    ↓
모든 대상 예약의 잠금 확보 완료
    ↓
슬롯 ID·예약 ID순으로 정렬
    ↓
슬롯 잠금 → 예약 취소 → 인원 복구
    ↓
회원 탈퇴 → flush → commit
```

후보 조회는 `List<Long>`만 반환한다. 예약 Entity를 일반 조회해 먼저 보관하는 대신, 실제 상태 판단은 잠금 조회로 얻은 Entity를 기준으로 수행한다.

재검사에서 상태가 바뀌었거나 시작 시각이 기준 시각 이후가 아니면 건너뛴다. 정상적인 경합으로 이미 처리된 예약 때문에 탈퇴 전체를 실패시키지 않기 위해서다. 시각 조건은 후보 조회와 동일하게 엄격한 `>`이며 정각도 대상에서 제외한다.

예약 하나를 잠근 뒤 바로 슬롯을 처리하지 않는다. **예약들의 잠금 확보 단계와 슬롯 처리 단계를 분리**하여 여러 대상이 있을 때 예약과 슬롯의 잠금 순서가 교차하지 않도록 했다. 이 변경 후 기존 탈퇴 경합 재현 테스트가 통과했다.

### 7.3 변경 파일

| 파일 | 내용 |
| --- | --- |
| [ReservationRepository.java](../../src/main/java/com/example/attractionreservation/domain/reservation/repository/ReservationRepository.java) | 사용자·관리자용 예약 잠금 조회 및 탈퇴 후보 ID 일반 조회 추가 |
| [ReservationService.java](../../src/main/java/com/example/attractionreservation/domain/reservation/service/ReservationService.java) | 사용자 취소·관리자 취소·완료·노쇼의 첫 조회를 잠금 조회로 연결 |
| [UserWithdrawalService.java](../../src/main/java/com/example/attractionreservation/domain/user/service/UserWithdrawalService.java) | 후보 ID 조회, 예약 단건 잠금·재검사, 예약 잠금과 슬롯 처리 단계 분리 |

이번 작업은 예약의 기존 활성 유니크 제약을 유지한다. KAN-91에서 새 컬럼이나 제약을 추가한 것은 아니다.

## 8. 기존 테스트의 mock 변경

동시성 테스트 통과 후 관련 기존 테스트에서 실패가 발생했다. 실제 Service는 새 잠금 메서드를 호출하지만, 기존 테스트는 옛 메서드에만 응답을 설정한 것이 원인이었다.

- 새 조회에 설정이 없어 빈 결과 반환 → 의도한 상태·시간 검증에 도달하기 전에 NOT_FOUND_RESERVATION 발생
- 호출하지 않는 옛 조회 설정이 남음 → UnnecessaryStubbingException 발생
- 탈퇴 후보 ID 목록이 설정되지 않음 → 취소 대상이 없어 기존 예약이 RESERVED로 유지

기대 ErrorCode나 상태 검증을 완화하지 않고 아래 네 파일만 변경했다.

| 파일 | 수정 |
| --- | --- |
| [ReservationCancelServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/ReservationCancelServiceTest.java) | 사용자 잠금 조회 mock으로 변경 |
| [AdminReservationCancelServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationCancelServiceTest.java) | 관리자 잠금 조회 mock으로 변경 |
| [AdminReservationStatusServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/reservation/service/AdminReservationStatusServiceTest.java) | 상태 변경의 잠금 조회 mock으로 변경 |
| [UserWithdrawalServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/user/service/UserWithdrawalServiceTest.java) | 후보 ID·각 예약 잠금 조회 mock 분리, fixture 예약 ID 추가, 모든 예약 잠금 후 슬롯 접근 순서 검증 |

mock 기반 순서 검증이 실제 DB 잠금의 증명은 아니므로, MySQL 기반 경합 테스트와 함께 확인했다.

## 9. 개선 후 실제 결과

### 9.1 취소·상태 변경 경합 6개

최신 XML의 suite 결과: `tests=6, skipped=0, failures=0, errors=0`.

| 시나리오 | 성공·실패 | 예약 A / B | 최종 인원 | 추가 확인 |
| --- | --- | --- | ---: | --- |
| 사용자 중복 취소 | 성공 1·거절 1 | CANCELLED / RESERVED | 1 | 중복 요청은 취소 불가 상태 예외 |
| 사용자·관리자 취소 | 성공 1·거절 1 | CANCELLED / RESERVED | 1 | 최신 실행은 사용자 취소 성공 |
| 취소·COMPLETED | 성공 1·거절 1 | COMPLETED / RESERVED | 2 | 취소가 거절되어 자리 복구 없음 |
| 취소·NO_SHOW | 성공 1·거절 1 | NO_SHOW / RESERVED | 2 | 취소가 거절되어 자리 복구 없음 |
| 탈퇴·사용자 취소 | 둘 다 성공 | CANCELLED / RESERVED | 1 | 회원 WITHDRAWN, 일반 취소 사유 NULL 유지 |
| 탈퇴·관리자 취소 | 둘 다 성공 | CANCELLED / RESERVED | 1 | 회원 WITHDRAWN, OPERATIONAL_ISSUE 유지 |

위 승자는 해당 실행 결과이며 테스트의 고정 조건이 아니다. 탈퇴 경합은 먼저 별도 취소가 완료되고 탈퇴가 이를 건너뛴 경우 두 요청이 성공할 수 있다.

최신 로그 원문의 핵심 값을 정리한 요약:

```text
사용자 중복 취소: 요청=2, 성공=1, 실패=1, 활성예약=1, reservedCount=1
사용자·관리자 취소: 요청=2, 성공=1, 실패=1, 활성예약=1, reservedCount=1
관리자 취소·COMPLETED: 성공=1, 실패=1, 예약A=COMPLETED, reservedCount=2
관리자 취소·NO_SHOW: 성공=1, 실패=1, 예약A=NO_SHOW, reservedCount=2
탈퇴·사용자 취소: 성공=2, 회원상태=WITHDRAWN, 활성예약=1, reservedCount=1
탈퇴·관리자 취소: 성공=2, 회원상태=WITHDRAWN, 활성예약=1, reservedCount=1
```

예약 완료·노쇼 경합에서 RESERVED로 남는 예약은 B 한 건이지만 기존 인원 유지 동작 때문에 reservedCount는 2다. 이를 취소 전용 시나리오의 `RESERVED 건수 = reservedCount` 검증과 혼동하지 않는다.

### 9.2 관련 회귀 테스트

사용자가 실행하고 통과했다고 확인한 명령:

```bash
./gradlew test --tests "*Reservation*" --tests "*UserWithdrawal*" --rerun-tasks
```

현재 `build/test-results/test/` XML을 읽어 집계한 결과:

| 범위 | 클래스 수 | 테스트 수 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 보고서에 남은 관련 실행 전체 | 36 | 237 | 0 | 0 | 0 |
| 위 실행에 포함된 취소 경합 클래스 | 1 | 6 | 0 | 0 | 0 |

6개는 237개에 포함된다. 패턴에는 예약 외에도 이름이 일치하는 어트랙션·슬롯·탈퇴 및 일부 Application 테스트가 포함됐다. **프로젝트 전체 테스트를 실행한 결과는 아니다.**

취소 경합 suite 시작 시각은 `2026-10-30T10:32:00.824Z`, KST로는 `2026-10-30 19:32:00.824`다. 보고서의 실제 실행 시각과 코드에서 고정한 테스트 기준 시각은 다르다.

문서 기록 시점 HEAD는 `7548d5723449d0a24948c4d0942079a6d7bc6760`이다. 기존 테스트 네 파일의 mock 수정은 미커밋 상태였으므로 이 HEAD만으로 최종 검증 상태까지 복원할 수 없다.

## 10. 다시 실행하고 로그 확인하기

Docker를 실행한 상태에서 프로젝트 루트의 Git Bash에서 아래 명령을 한 줄로 실행한다.

```bash
# 취소·상태 변경·탈퇴 경합 6개
./gradlew test --tests "*ReservationCancellationConcurrencyIntegrationTest" --rerun-tasks

# 관련 회귀 검증
./gradlew test --tests "*Reservation*" --tests "*UserWithdrawal*" --rerun-tasks
```

결과 확인 위치:

```text
build/reports/tests/test/index.html
build/test-results/test/*ReservationCancellationConcurrency*.xml
```

핵심 출력 검색:

```bash
grep -n -E "취소 결과|경합 결과|KAN-91 MySQL|HOLDS THE LOCK|WAITING FOR THIS LOCK|ROLL BACK" build/test-results/test/*ReservationCancellationConcurrency*.xml
```

일반 SQL 출력만으로 transaction별 잠금 순환을 확정하지 않는다. deadlock이 다시 발생하면 서버 진단의 **실행 SQL·보유 인덱스·대기 인덱스·rollback 대상**을 함께 읽는다.

이 문서의 수정 전 결과와 deadlock 발췌는 당시 실제로 읽었던 보고서에서 정리했다. 현재 보고서는 개선 후 실행으로 덮어써졌으며, 과거 XML·서버 로그 전체를 별도 파일로 보관한 상태는 아니다. 문서 작성 과정에서는 테스트를 다시 실행하지 않았다.

## 11. 검증 범위와 남은 사항

- CountDownLatch는 시작 시점을 맞추지만 모든 SQL 실행 순서를 고정하지 않는다. 통과 한 번을 모든 경합의 안전성 보장으로 확대하지 않는다.
- 탈퇴 경합 fixture에는 탈퇴 회원의 대상 예약 A가 한 건이다. 여러 예약·여러 슬롯을 가진 회원의 실제 DB 경합은 추가 검증 대상이다. 기존 단위 테스트의 복수 예약 순서 검증과 구분한다.
- 완료·노쇼 각각의 경합은 통과했지만, 모든 가능한 승자 순서를 강제로 재현한 것은 아니다.
- 테스트는 Service와 DB 범위다. Controller의 HTTP 오류 응답, 인증·권한 검증까지 완료했다고 판단하지 않는다.
- 실제 session isolation level, 실행 계획 전체, 처리량·잠금 대기·timeout은 수집하거나 측정하지 않았다.
- 완료·노쇼 시 인원을 유지하는 기존 동작과 문서 정원 산식의 운영상 집계 의미는 별도 확인 사항이다. 이번 변경으로 인원 정책을 확장하지 않았다.
- 진단을 위해 서버 로그 옵션을 켠 것은 이 테스트 클래스의 MySQL에 한정된다. 운영 DB 설정을 변경하지 않았다.
- 기존 활성 예약 유니크 제약을 실제 운영 DB에 적용했는지는 이 문서에서 검증하지 않았다. KAN-87 migration의 실행 상태와 별도로 관리한다.

## 12. 완료 체크리스트

- [x] 초기 인원 2명에서 같은 예약 중복 취소 재현
- [x] 사용자 취소의 예약 잠금 적용 및 검증
- [x] 관리자 강제 취소의 예약 잠금 적용 및 검증
- [x] 완료·노쇼 변경의 예약 잠금 적용 및 검증
- [x] 탈퇴·사용자 및 탈퇴·관리자 취소 경합 검증
- [x] Testcontainers 서버 로그로 deadlock 보유·대기 인덱스와 rollback 대상 확인
- [x] 탈퇴 후보 ID 조회와 PK 단건 잠금 획득 분리
- [x] 잠금 획득 후 상태·시각 재검사 및 처리된 예약 건너뛰기
- [x] 모든 예약 잠금 확보 후 슬롯 처리, 슬롯 접근 순서 유지
- [x] 기존 테스트 네 파일의 mock·조회 순서 검증 수정
- [x] 취소 경합 6개 및 관련 보고서 237개 테스트 통과 확인
- [ ] 복수 예약·복수 슬롯을 가진 탈퇴 회원의 실제 DB 경합 추가 검증
- [ ] 필요한 추가 실행 순서·isolation level·성능 측정 범위 결정

후속 실험은 목적·데이터·실행 명령·기대값·실제 로그·판정을 구분해 이 문서에 이어 기록한다.
