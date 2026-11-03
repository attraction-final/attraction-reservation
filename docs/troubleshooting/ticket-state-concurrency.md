# 티켓 입장·자동 종료의 상태 변경 유실 트러블슈팅

> **KAN-100 · 중복 입장 및 폐장 경계 변경 유실 재현 후 개선**
>
> 같은 티켓의 동시 스캔이 모두 성공하는 문제와, 자동 종료가 정상 입장 기록을 덮어쓰는 문제를 실제 MySQL에서 재현했다.
> 입장과 자동 종료에 티켓 행의 비관적 락을 적용했다.
> 최신 로컬 XML에서 상태 동시성 테스트 4개를 포함한 티켓·환불 관련 50개 suite, 472개 테스트의 통과를 확인했다.

## 1. 확인한 문제와 결과

### 문제 A: 같은 티켓으로 두 요청 모두 입장 성공

- 기대: 입장 성공 1건, 중복 입장 거절 1건
- 수정 전: 입장 성공 2건, 거절 0건
- 수정 후: 선행 입장 commit 이후 후행 요청이 최신 상태를 확인하여 거절됨
- 최초 성공 요청의 `enteredAt` 유지 확인

### 문제 B: 입장 성공 기록을 자동 종료가 덮어씀

- 기대: 폐장 전에 입장한 티켓이 `USED`가 되고 입장 시각이 유지됨
- 수정 전: 최종 상태가 `EXPIRED`, `enteredAt=null`
- 수정 후: 입장 선행이면 `USED`, 최초 입장 시각 유지, 종료 시각은 방문일 KST 22시

### 추가 검증: 환불용 잠금과 자동 종료의 연동

- 환불 경로에서 사용하는 잠금 조회로 티켓을 `REFUNDED`로 변경하는 동안 자동 종료가 대기함
- commit 이후 환불 티켓을 종료 대상에서 제외하고 처리 건수 0 반환
- `REFUNDED`, `enteredAt=null`, `endTime=null` 유지
- 환불 API의 기한 검증과 PG 처리를 포함하는 전체 환불 테스트는 아님

## 2. 기존 코드와 문서 기준

작업 전 티켓 상태를 변경하는 경로의 보호가 달랐다.

- **입장:** 일반 `findByCode()` 조회 후 `entryTicket()` 호출
- **자동 종료:** Querydsl 일반 조회 후 `expireIfClosed()` 호출
- **환불:** `PESSIMISTIC_WRITE`로 조회 후 티켓 상태 변경
- `Ticket`에는 `@Version`이 없었고 이번 작업에서도 추가하지 않았다.

각 메서드에는 상태 검사가 있었지만, 다른 transaction이 같은 상태를 읽고 있는지까지 조정하지는 못했다.

읽기 전용 원본 `sources/비즈니스 규칙.md`에서 확인한 관련 기준은 다음과 같다. 원본 문서는 변경하지 않았다.

- 미사용 티켓은 입장 처리 시 `IN_USE`로 변경된다.
- 사용 이력이 없는 티켓은 폐장 이후 `EXPIRED`로 변경된다.
- 사용 중인 티켓은 폐장 이후 `USED`로 변경된다.
- 사용 완료·환불·만료 상태는 변경할 수 없다.
- **BR-TKT-07:** 미사용·사용 완료·환불·만료 티켓으로 예약·추천을 사용할 수 없다.
- **BR-PAY-12:** 방문일 당일부터 환불할 수 없다.

현재 [RefundService.java](../../src/main/java/com/example/attractionreservation/domain/refund/service/RefundService.java)에는 KST 기준 방문일 당일 이후 환불을 거절하는 검증이 있다. 따라서 과거 리뷰의 “환불 기한 누락으로 당일 환불과 입장이 경합한다”는 설명을 이번 재현의 전제로 사용하지 않았다. 환불 기능 전체의 요구사항 충족을 검토한 것은 아니다.

이번에는 기존 순차 입장 동작을 유지하여 중복 스캔을 상태 오류로 거절하는 기준을 사용했다. 이미 입장한 결과를 다시 성공으로 반환하는 멱등 응답 방식으로 변경하지 않았다.

## 3. 재현 환경

[TicketStateConcurrencyIntegrationTest.java](../../src/test/java/com/example/attractionreservation/domain/ticket/service/TicketStateConcurrencyIntegrationTest.java)를 추가했다.

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers의 실제 MySQL `mysql:9.6` 사용
- H2로 대체하지 않도록 `@AutoConfigureTestDatabase(replace = NONE)` 설정
- 테스트 메서드의 기본 transaction은 `NOT_SUPPORTED`
- fixture 준비와 최종 상태 조회는 `TransactionTemplate`로 별도 commit
- 각 작업 스레드에서 실제 Service transaction 실행
- 테스트 종료 시 fixture만 FK 참조 순서에 따라 삭제
- Redis·HTTP Controller·Security filter chain은 이 테스트의 실행 범위에 포함하지 않음

방문일은 2026-11-02로 고정했다. 입장은 스레드별 `Instant.now()` static mock으로 시간을 제어하고, 자동 종료는 주입한 고정 `Clock`을 사용했다.

```text
중복 스캔 A: KST 14:00:00
중복 스캔 B: KST 14:00:01
폐장 직전 입장: KST 21:59:59
자동 종료: KST 22:00:00
```

static mock은 각 작업 스레드 안에서 열고 닫는다. `Instant`의 나머지 메서드는 실제 동작을 유지하며, 고정할 시각 값은 mock 시작 전에 생성했다.

## 4. 첫 번째 재현: 동일 티켓의 중복 입장

### 실행 순서 제어

두 요청을 동시에 시작하기만 하면 조회·commit 순서가 매번 달라져 문제가 드러나지 않을 수 있다. 수정 전 테스트에서는 일반 조회 구간을 제어하여 두 transaction이 모두 `UNUSED`를 읽도록 했다.

1. 실제 DB에 미사용 티켓 1장을 저장하고 commit한다.
2. 두 스레드에서 같은 티켓 코드로 입장 Service를 호출한다.
3. Repository spy의 조회 구간에서 원래 조회와 같은 JPQL을 실제 DB에 실행한다. mock 티켓을 반환하지 않는다.
4. 두 transaction 모두 `UNUSED`와 `enteredAt=null`을 읽은 뒤 대기한다.
5. 대기를 풀고 두 요청을 진행한다.
6. 응답과 최종 DB 상태를 확인한다.

### 실제 실패 결과

2026-11-02 17:36:25 KST에 시작한 보고서에서 다음 결과를 확인했다.

```text
동시 입장 성공 수: expected size 1, actual size 2
중복 입장 거절 수: expected size 1, actual size 0

성공 응답의 enteredAt:
- 2026-11-02T05:00:00Z = KST 14:00:00
- 2026-11-02T05:00:01Z = KST 14:00:01
```

같은 티켓에 대한 두 요청이 각자 읽은 `UNUSED` 상태로 검증을 통과했다. 최종 티켓이 한 행이라는 사실만으로 중복 성공 응답까지 방지되는 것은 아니었다.

## 5. 첫 번째 개선: 입장에 코드 기반 잠금 조회 적용

[TicketRepository.java](../../src/main/java/com/example/attractionreservation/domain/ticket/repository/TicketRepository.java)에 다음 메서드를 추가했다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT t FROM Ticket t WHERE t.code = :code")
Optional<Ticket> findByCodeWithLock(@Param("code") String code);
```

[TicketService.java](../../src/main/java/com/example/attractionreservation/domain/ticket/service/TicketService.java)의 `createTicketEntry()`가 이 조회를 사용하도록 변경했다.

```text
잠금 조회
→ 현재 시각 취득
→ 입장 상태·방문일·시간 검증
→ IN_USE 변경 및 enteredAt 기록
→ transaction commit
```

현재 시각 취득을 잠금 조회 뒤에 유지했다. 잠금을 기다린 요청이 대기 시작 전의 시각으로 입장 가능 여부를 판단하지 않도록 하기 위해서다. 기존 `@Transactional`과 `TICKET_NOT_FOUND` 예외 처리는 유지했다.

개선 후 후행 요청은 선행 입장의 commit 이후 `IN_USE`를 확인하고 `TICKET_ENTRY_NOT_ALLOWED_STATUS`로 거절된다.

수정 전에는 두 일반 조회가 모두 끝나도록 대기시켰지만, 잠금 적용 후에는 두 요청이 함께 조회를 마칠 수 없다. 따라서 재현용 spy를 제거하고 선행 transaction의 commit 시점을 제어하는 실제 Repository 회귀 테스트로 바꿨다.

[TicketEntryServiceTest.java](../../src/test/java/com/example/attractionreservation/domain/ticket/service/TicketEntryServiceTest.java)도 `findByCodeWithLock()`에 맞춰 mock과 호출 검증을 수정했다.

## 6. 두 번째 재현: 입장만 잠가서는 자동 종료의 오래된 조회를 막지 못함

입장 경로를 개선한 뒤에도 자동 종료는 일반 조회를 사용했다. 일반 SELECT는 다른 transaction의 행 잠금 때문에 반드시 대기하는 것이 아니므로, 입장 commit 이전의 값을 읽을 수 있었다.

### 재현 순서

```text
입장 transaction:
  21:59:59 기준 잠금 조회 → IN_USE 변경 → commit 대기

자동 종료 transaction:
  22:00:00 기준 일반 조회 → 기존 UNUSED 확인 → EXPIRED 변경 → commit 대기

입장 transaction commit
자동 종료 transaction commit
```

이때 자동 종료의 UPDATE가 실행될 때 잠금을 기다릴 수는 있어도, 이미 읽은 엔티티 값이 자동으로 최신 값으로 바뀌지는 않는다. 늦게 반영한 오래된 상태가 정상 입장 기록을 덮어썼다.

### 실제 실패 결과

2026-11-02 18:03:20 KST에 시작한 보고서는 2개 테스트 중 폐장 경계 테스트 1개가 실패한 결과였다. 기존 중복 스캔 테스트는 통과했다.

```text
최종 상태:
  expected: USED
   but was: EXPIRED

enteredAt:
  expected: 2026-11-02T12:59:59Z
   but was: null
```

종료 직전 상태가 `IN_USE`였는데도 자동 종료가 이전 `UNUSED`를 기준으로 `EXPIRED`를 저장했고, 입장 시각도 유실됐다.

## 7. 두 번째 개선: Querydsl 자동 종료 조회에도 잠금 적용

[TicketCustomRepositoryImpl.java](../../src/main/java/com/example/attractionreservation/domain/ticket/repository/TicketCustomRepositoryImpl.java)의 `findExpirationTargets()`에 잠금 모드를 추가했다.

```java
.orderBy(ticket.id.asc())
.limit(batchSize)
.setLockMode(LockModeType.PESSIMISTIC_WRITE)
.fetch();
```

- 기존 방문일·상태 조건 유지
- ID 오름차순과 batch 제한 유지
- [TicketExpirationService.java](../../src/main/java/com/example/attractionreservation/domain/ticket/service/TicketExpirationService.java)의 기존 `@Transactional` 유지
- 종료 상태 결정과 방문일 KST 22시의 종료 시각 계산은 기존 엔티티 로직 유지

이제 입장이 먼저 잠근 경우 자동 종료는 조회 단계에서 기다린다. 입장 commit 이후 최신 `IN_USE`를 읽고 `USED`로 변경하므로, 정상 입장 기록을 유지한다.

반대로 자동 종료가 먼저 잠그면 후행 입장은 종료 commit까지 기다린다. 이후 `EXPIRED` 상태를 확인하여 거절되고, 만료 상태와 종료 시각이 유지된다.

이 작업은 티켓 행 잠금의 적용 범위를 맞춘 것이다. KAN-95의 상품 수정과 달리 클라이언트 version 비교나 Ticket의 `@Version`, 추가 DB 컬럼은 도입하지 않았다.

## 8. 환불용 잠금과의 연동 확인

기존 환불용 잠금 조회와 새 자동 종료 잠금이 같은 티켓에서 조정되는지도 검증했다.

1. 외부 transaction 안에서 실제 `findRefundTicketsForUpdate()`를 호출한다.
2. 조회한 티켓에 `refund()`를 호출하여 `REFUNDED`로 변경하고 commit을 대기한다.
3. 별도 transaction에서 자동 종료를 시작한다.
4. 환불 transaction을 commit한다.
5. 자동 종료 처리 건수가 0이며 환불 상태가 유지되는지 확인한다.

이 테스트는 **환불의 티켓 잠금·상태 변경 부분만 검증**한다. 환불 기한을 검증하는 `RefundService`, 결제 상태 변경, PG 호출은 수행하지 않았다.

따라서 정상 환불 업무가 폐장 시각에 허용된다는 의미도 아니고, 방문일 당일 환불 불가 정책을 우회한 실제 환불 API 재현도 아니다. 서로 다른 티켓 변경 경로가 같은 행 잠금을 사용할 때 상태 변경을 보존하는지 확인한 기술적 검증이다.

## 9. 최신 테스트 결과와 증거 범위

문서 작성 시 로컬 XML에서 다음 결과를 직접 확인했다.

```text
대상: 티켓·환불 관련 회귀 검증
범위: 50개 suite, 472개 테스트
시작 범위: 2026-11-02 18:58:04 ~ 19:00:16 KST
tests=472, failures=0, errors=0, skipped=0

포함: TicketStateConcurrencyIntegrationTest
시작: 2026-11-02 18:59:50 KST
tests=4, failures=0, errors=0, skipped=0
```

확인한 케이스는 다음과 같다.

1. **중복 스캔:** 성공 1건, 상태 오류 1건, 최초 `enteredAt` 보존
2. **입장 선행:** 자동 종료 후 `USED`, `enteredAt=21:59:59`, `endTime=22:00:00` KST
3. **자동 종료 선행:** 입장 거절, `EXPIRED`, 입장 시각 없음, 종료 시각 유지, 재종료 처리 건수 0
4. **환불용 잠금 선행:** 자동 종료 대상 제외, 처리 건수 0, `REFUNDED`와 기존 시각 필드 유지

사용자는 앞선 입장 동시성·입장 단위 테스트와 각 단계의 상태 통합 테스트에 이어, 티켓·환불 관련 회귀 테스트도 통과했다고 보고했다. 이후 로컬 XML 50개를 집계하여 위 472개 통과 결과를 직접 확인했다. 이 수치는 해당 필터로 실행한 관련 테스트 결과이며 프로젝트 전체 테스트 수가 아니다.

이전 실패 XML은 재실행으로 교체됐다. 수정 전 결과는 당시 실제 보고서에서 확인한 내용을 기록했으며, 이전 전체 XML·SQL 로그가 별도 보존되어 있는 것은 아니다.

### 실행 명령어

Docker 실행 후 Git Bash에서 각 명령어를 한 줄로 실행한다.

상태 동시성 테스트:

```bash
./gradlew test --tests "*TicketStateConcurrencyIntegrationTest" --rerun-tasks
```

입장 단위 테스트를 함께 확인할 때:

```bash
./gradlew test --tests "*TicketStateConcurrencyIntegrationTest" --tests "*TicketEntryServiceTest" --rerun-tasks
```

티켓·환불 관련 회귀 검증:

```bash
./gradlew test --tests "*Ticket*" --tests "*Refund*" --rerun-tasks
```

관련 회귀 명령어 실행 후 로컬 보고서에서 472개 테스트의 통과를 확인했다. 상태 통합 테스트 4개는 이 집계에 포함되므로 별도로 더하지 않는다. 문서 작성·갱신 과정에서는 테스트를 실행하지 않고 사용자가 실행한 결과를 확인했다.

## 10. 검증 한계와 남은 확인 사항

- 테스트는 실제 MySQL에서 Service transaction을 겹쳐 실행한다. HTTP 요청의 동시 처리나 관리자 권한 검증은 포함하지 않는다.
- 선행 transaction의 commit을 제어하여 실행 순서를 고정한다. 스레드를 무작위로 많이 실행하는 부하 테스트는 아니다.
- 후행 요청이 500ms 동안 완료되지 않는지 확인하지만, MySQL의 내부 lock wait 정보를 직접 수집한 것은 아니다. 대기 검증과 최종 상태·시각·예외를 함께 확인했다.
- 환불과 자동 종료의 기술적 연동을 확인했지만, 전체 환불 API·PG 처리나 입장·환불 간 모든 실행 순서를 검증한 것은 아니다.
- batch 제한을 유지했지만 실제 DB의 잠금 범위·대기 시간·실행 계획은 별도로 측정하지 않았다. 반환 행 수가 곧 실제 잠금 범위 전체라고 단정하지 않는다.
- 여러 배치 실행 인스턴스의 경쟁, 복수 티켓과 결제·주문 잠금이 함께 얽힌 모든 deadlock 가능성을 검증한 것은 아니다.
- “대기 시작은 폐장 전이었지만 잠금을 획득할 때 폐장이 지난 입장”은 현재 시각 취득 위치를 코드로 확인했으며, 별도의 시간 전진 테스트로 검증한 것은 아니다.
- `TicketExpirationService`의 기존 `TODO: 동시성` 주석은 아직 남아 있다. 이번에 확인한 방어 범위와 미검증 범위를 고려하여 후속 정리할 수 있다.

## 11. 작업 상태

- [x] 동일 티켓 동시 입장 성공 2건 재현
- [x] 입장용 코드 기반 잠금 조회 적용
- [x] 중복 입장 거절 및 최초 입장 시각 보존 확인
- [x] 입장·자동 종료 경쟁에서 `EXPIRED`, `enteredAt=null` 변경 유실 재현
- [x] Querydsl 자동 종료 조회에 비관적 락 적용
- [x] 입장·자동 종료의 두 선행 순서 검증
- [x] 환불용 티켓 잠금과 자동 종료의 연동 검증
- [x] 최신 상태 통합 테스트 4개 통과 확인
- [x] 티켓·환불 관련 회귀 테스트 50개 suite, 472개 통과 확인
- [ ] 실제 부하·배치 잠금 범위 및 더 넓은 복합 경합 검토

작성 기준: 2026-11-02, `fix/KAN-100-ticket-state-concurrency`, HEAD `5a8f8c7`.
이 문서는 해당 시점의 코드·실제로 확인한 보고서·대화에서 사용한 처리 기준을 기록한다.
