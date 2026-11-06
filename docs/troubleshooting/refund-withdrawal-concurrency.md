# 미완료 환불과 회원 탈퇴의 정합성 트러블슈팅

> **KAN-120 · 미완료 환불이 있는 회원의 탈퇴 제한 및 환불 요청과의 동시 실행 보호**
>
> 환불 요청 시 티켓이 먼저 `REFUNDED`로 변경되어 기존 미사용 티켓 검사만으로는 미완료 환불을 발견하지 못했다.
> 환불 상태 검사를 추가한 뒤에도 동시 실행에서 검증이 누락되는 상황을 재현했다.
> 환불 요청과 탈퇴가 같은 회원 행을 잠그도록 변경하고, 탈퇴 회원의 새 환불 요청을 차단했다.
> 양쪽 선행 순서의 동시성 테스트 통과를 사용자에게 확인받았다. 소유자 ID 사전 조회의 snapshot 영향 검증과 전체 회귀 검증은 아직 남아 있다.

## 1. 문제와 업무 기준

확인한 읽기 전용 원본 문서의 기준은 다음과 같다.

- 사용자 시나리오 **1-5**: 미사용 입장권의 환불이 완료되기 전까지 탈퇴를 보류한다.
- 사용자 시나리오 **3-3**: 환불 완료 후 결제·입장권 상태를 반영하고, 실패 시 완료로 표시하지 않는다.
- **BR-USR-09**: 탈퇴 시 이용 예정 예약과 미사용 입장권의 처리 방식을 안내한다.
- **BR-PAY-14**: 환불 완료 시 티켓·결제 상태를 같은 트랜잭션에서 변경한다.

현재 구현은 환불 요청 저장 시 티켓을 먼저 `REFUNDED`로 변경한다. 이는 환불 중 입장과 중복 환불을 차단하기 위한 처리이며, 금전 환불 완료를 뜻하지 않는다.

이번 작업에서는 이 사용 제한을 유지하고, 탈퇴 가능 여부를 실제 `Refund.status`로 확인한다. 티켓의 `REFUNDED` 의미와 BR-PAY-14 사이의 차이를 해소한 것은 아니다. `REFUND_PENDING` 도입 등 상태 구조 변경은 이번에 수행하지 않았다.

### 작업 중 결정한 정책

- `REQUESTED`, `PROCESSING`, `PG_PENDING`, `FAILED`, `DEAD` 환불이 있으면 탈퇴를 보류한다.
- `FAILED`는 재시도 대기, `DEAD`는 수동 확인 대상이므로 완료로 취급하지 않는다.
- `COMPLETED`만 존재한다면 환불 검사는 탈퇴를 막지 않는다. 다른 탈퇴 제한은 계속 적용한다.
- 탈퇴한 회원의 결제에는 사용자·관리자 모두 새 환불 요청을 생성할 수 없다.
- 탈퇴 상태 차단은 `WITHDRAWN`에 한정한다. 정지 회원까지 추가로 차단하는 정책은 도입하지 않았다.
- 일반 사용자의 타인 결제 요청은 소유자의 탈퇴 여부와 관계없이 기존 `PAYMENT_NOT_FOUND` 처리를 유지한다.

위 탈퇴 회원 환불 차단 정책은 작업 중 사용자가 결정한 사항이다. 원본 문서에 이미 명시된 정책으로 간주하지 않으며, 원본 `sources`는 변경하지 않았다.

## 2. 최초 문제: 환불 요청 이후 UNUSED 검사로 탈퇴를 막지 못함

[RefundService.saveRefund()](../../src/main/java/com/example/attractionreservation/domain/refund/service/RefundService.java)는 환불 요청 트랜잭션에서 다음을 수행한다.

```text
환불할 티켓 잠금 및 검증
→ 티켓을 REFUNDED로 변경
→ 환불을 REQUESTED로 저장
→ 커밋 후 PG 처리
```

반면 [UserWithdrawalService.withdraw()](../../src/main/java/com/example/attractionreservation/domain/user/service/UserWithdrawalService.java)는 유효일이 오늘 이후인 `UNUSED` 티켓만으로 잔여 티켓을 검사했다.

```text
환불 PG 확정 대기
→ 티켓은 이미 REFUNDED
→ 다른 유효한 UNUSED 티켓 없음
→ 탈퇴 검사를 통과할 수 있음
```

실제 DB에 `REFUNDED` 티켓과 `PG_PENDING` 환불을 저장한 재현 테스트에서, 탈퇴를 거부할 예외가 발생하지 않아 실패했다.

### 첫 수정

- [RefundRepository](../../src/main/java/com/example/attractionreservation/domain/refund/repository/RefundRepository.java)에 `existsByPayment_Order_User_IdAndStatusIn()`을 추가했다.
- 회원 → 결제 소유 관계와 전달한 상태 목록을 기준으로 존재 여부를 조회한다.
- `UserWithdrawalService`가 미완료 상태 다섯 개를 전달한다.
- 미완료 환불이 있으면 예약 취소·정원 복구·회원 탈퇴 전에 `USER_WITHDRAWAL_INCOMPLETE_REFUND`로 거부한다.
- 응답 상태는 `409 Conflict`다.

이 수정으로 이미 커밋된 미완료 환불의 탈퇴 차단을 확인했다.

## 3. 두 번째 문제: 검사 실행 중 환불 요청이 커밋되는 경우

환불 상태 조회를 추가한 것만으로 동시 실행까지 해결되지는 않았다.

### 재현 환경

[RefundWithdrawalConcurrencyIntegrationTest](../../src/test/java/com/example/attractionreservation/RefundWithdrawalConcurrencyIntegrationTest.java)를 사용했다.

- `@DataJpaTest`, `@ActiveProfiles("test")`
- Testcontainers MySQL `mysql:9.6`
- `ddl-auto=create-drop`, Hikari 최대 커넥션 4개
- 기본 테스트 트랜잭션 `NOT_SUPPORTED`
- 각 작업은 별도 스레드·트랜잭션에서 실제 Service·Repository 실행
- `CountDownLatch`와 Repository의 AOP advisor로 작업 순서 제어
- advisor는 쿼리 결과를 바꾸거나 DB 잠금을 대체하지 않음
- 테스트 종료 후 해당 fixture만 외래 키 순서에 맞춰 정리

### fixture를 이용일이 지난 UNUSED 티켓으로 정한 이유

미래의 `UNUSED` 티켓을 사용하면 기존 미사용 티켓 검사만으로 탈퇴가 거부되어 환불 상태 검사의 경합을 놓칠 수 있다.

따라서 이용일이 지났지만 아직 `UNUSED`인 티켓에 관리자가 환불을 요청하도록 구성했다. 관리자는 환불 기한 제한을 면제받지만 티켓은 `UNUSED`여야 한다는 기존 정책을 사용한다. 자동 만료 작업이 아직 처리하지 않은 상황을 모델링한 것이며, `EXPIRED` 티켓 환불을 허용한 것이 아니다.

### 재현 순서

```text
환불 트랜잭션: 실제 관리자 환불 요청 저장·flush 후 커밋 대기
탈퇴 트랜잭션: 미사용 티켓 쿼리 실행 후 환불 검사 직전에 대기
환불 트랜잭션: 커밋
탈퇴 트랜잭션: 환불 존재 여부 조회 재개
```

2026-11-05 20:54:41 KST에 시작된 실패 결과에서 다음을 확인했다.

```text
tests=1, failures=1, errors=0
expected: USER_WITHDRAWAL_INCOMPLETE_REFUND
 but was: null
```

테스트의 `null`은 탈퇴 서비스가 `BusinessException` 없이 반환됐음을 뜻한다. 테스트 준비 실패가 아니라 실제 탈퇴 거부 검증에서 실패한 것이다.

SQL 로그에는 미사용 티켓 조회 → 환불의 커밋 후 완료 로그 → 환불 존재 조회 → 탈퇴 처리 진행 순서가 남았다.

### 원인 분석과 확인 범위

탈퇴는 회원 행을 잠그지만, 당시 환불 요청은 같은 회원 잠금에 참여하지 않았다. 따라서 두 작업이 동시에 진행될 수 있었다.

환불이 커밋된 뒤 존재 조회를 실행해도 앞선 일반 조회가 만든 snapshot을 계속 사용하면 새 환불을 놓칠 수 있다. 관측된 실행 순서는 이 설명과 일치한다. 다만 해당 실패 실행에서 DB 세션의 격리 수준 값을 직접 조회한 것은 아니므로, 격리 수준 자체를 실측했다고 기록하지 않는다.

조회 한 번을 추가하는 것과 검사·상태 변경을 함께 동시성 보호에 참여시키는 것은 다른 문제였다.

## 4. 개선: 회원 잠금을 공유하고 탈퇴 상태를 다시 검증

### 잠금 순서

[PaymentRepository.findUserIdByPgId()](../../src/main/java/com/example/attractionreservation/domain/payment/repository/PaymentRepository.java)로 결제 소유자 ID만 조회한 뒤 다음 순서로 잠근다.

```text
소유자 ID 사전 조회 — 잠금 없음
→ 소유자 회원 행 PESSIMISTIC_WRITE
→ 결제 행 PESSIMISTIC_WRITE
→ 대상 티켓 행 잠금
→ 환불 검증·저장
```

- 회원 잠금은 기존 `UserRepository.findByIdForUpdate()`를 재사용한다.
- `RefundService.getPaymentWithLock()`에서 회원을 먼저 잠근다.
- 사용자·관리자의 전체·부분 환불 요청이 이 공통 경로를 사용한다.
- 실제 트랜잭션은 공개 환불 요청 메서드에서 시작하며, 회원 잠금도 그 트랜잭션이 끝날 때 해제된다.
- 사전 조회에서 Payment·User 엔티티를 미리 로딩하지 않는다. 단, scalar 조회라고 DB snapshot 영향까지 사라지는 것은 아니다.
- PG 외부 호출 동안 회원 잠금을 유지하도록 범위를 확장하지 않았다.

### 검증 순서와 결제 정보 노출 방지

탈퇴 검사를 잠금 메서드 안에 바로 넣으면, 일반 사용자가 타인의 결제를 요청할 때 소유권 검사보다 탈퇴 오류가 먼저 발생할 수 있었다.

따라서 `validateRefundableOwner()`로 분리해 다음 위치에서 호출한다.

```text
일반 사용자:
회원·결제 잠금 → 본인 결제 확인 → 탈퇴 상태 → 결제 상태 → 기한

관리자:
회원·결제 잠금 → 탈퇴 상태 → 결제 상태
```

탈퇴 소유자는 `REFUND_USER_WITHDRAW`와 `409 Conflict`로 거부한다. 타인 결제를 요청한 일반 사용자에게는 기존 `PAYMENT_NOT_FOUND`를 반환한다.

## 5. 검증한 시나리오

### 미완료 환불 차단 및 정상 탈퇴

[UserWithdrawalIntegrationTest](../../src/test/java/com/example/attractionreservation/UserWithdrawalIntegrationTest.java):

- 미완료 상태 다섯 개에서 탈퇴 거부와 정확한 ErrorCode 확인
- 거부 후 회원의 `ACTIVE` 상태와 탈퇴 일시 유지
- `COMPLETED` 환불만 있고 다른 제한 조건이 없으면 정상 탈퇴
- 다른 회원에게만 미완료 환불이 있으면 본인은 정상 탈퇴

사용자가 관련 탈퇴 단위·통합 테스트의 통과를 확인했다. 기존 단위 테스트의 생성자에도 새 `RefundRepository` 의존성을 반영했다.

### 동시 실행: 환불이 먼저인 경우 1개

- 실제 환불 요청 트랜잭션을 커밋 직전에 대기시킨다.
- 탈퇴 요청을 실행하고 환불 커밋 후 검사를 진행시킨다.
- 탈퇴가 `USER_WITHDRAWAL_INCOMPLETE_REFUND`로 거부된다.
- 최종 DB에서 회원은 `ACTIVE`, 환불은 `REQUESTED`, 티켓은 `REFUNDED`다.

### 동시 실행: 탈퇴가 먼저인 경우 2개

- 실제 탈퇴를 수행한 외부 트랜잭션이 커밋 전에 회원 잠금을 유지한다.
- 관리자 전체·부분 환불 요청을 각각 실행한다.
- 탈퇴 커밋 후 환불이 `REFUND_USER_WITHDRAW`로 거부된다.
- 회원은 `WITHDRAWN`이고 환불 기록은 생성되지 않는다.
- 티켓 `UNUSED`, 결제 `PAID`, 주문 `COMPLETED`, 상품 재고 99가 유지된다.

사용자가 동시성 클래스의 세 케이스 통과를 확인했다. 500ms 동안 요청이 미완료인지 확인하는 검사는 스레드 제어의 보조 수단이며, MySQL 잠금 대기 테이블을 조회한 증거는 아니다. 최종 업무 오류와 DB 상태도 함께 검증한다.

### 일반 사용자 소유권 회귀 2개

[RefundServiceTest](../../src/test/java/com/example/attractionreservation/domain/refund/service/RefundServiceTest.java):

- 탈퇴한 타인의 결제를 전체·부분 환불 요청해도 `PAYMENT_NOT_FOUND` 반환
- 티켓 조회·환불 수량 계산·환불 저장이 실행되지 않음
- 공통 fixture에 소유자 ID 조회와 회원 잠금 mock 추가
- 결제 없음 테스트의 mock을 최초 소유자 조회 단계에 맞춰 수정

문서 작성 시 확인한 최신 XML은 다음과 같다.

```text
RefundServiceTest
tests=63, failures=0, errors=0, skipped=0
시작 시각: 2026-11-05 23:31:51 KST
```

Gradle 실행마다 이전 결과 파일이 덮어써질 수 있다. 위 63개는 최신 XML에서 직접 확인한 결과이며, 앞선 탈퇴·동시성 테스트는 사용자의 개별 실행 통과 보고와 구분한다. 모든 테스트를 한 번에 실행해 통과한 결과는 아직 확인하지 않았다. 이 작업에서 에이전트는 테스트를 직접 실행하지 않았다.

## 6. 실행 명령

각 명령은 사용자가 실행한다.

```bash
./gradlew test --tests "*UserWithdrawalIntegrationTest" --tests "*UserWithdrawalServiceTest" --rerun-tasks
```

```bash
./gradlew test --tests "*RefundWithdrawalConcurrencyIntegrationTest" --rerun-tasks
```

```bash
./gradlew test --tests "*RefundServiceTest" --rerun-tasks
```

## 7. 남은 검증과 한계

- [ ] 소유자 ID 사전 조회 후 다른 환불이 커밋되는 사용자·관리자 부분 환불 회귀 테스트를 실행한다. 테스트는 추가했으나 아직 실행하지 않았다.
- [ ] 환불 요청 네 경로에 적용한 READ_COMMITTED 보완을 검증한다. 격리 수준 변경만으로 모든 경합이 해결됐다고 판단하지 않는다.
- [ ] 관련 환불·결제·티켓·회원 탈퇴 회귀 테스트를 실행하고 최신 결과를 확인한다.
- [ ] 탈퇴 회원 환불 차단 정책을 팀 문서에 반영한다. 이 문서 작성으로 원본 요구사항이 자동 변경되지는 않는다.
- [ ] 티켓 `REFUNDED`와 금전 환불 완료의 의미 차이 및 상태 구조 개선 여부를 팀과 정리한다.

PG 호출, 실패·재시도, 환불 완료 시 결제·주문·재고 반영 전체를 이번 새 테스트가 검증한 것은 아니다. 기존 테스트의 회귀 확인과 별개로 취급한다.

현재까지 확인한 결과는 **미완료 환불 탈퇴 제한, 환불·탈퇴 양쪽 선행 순서의 처리, 타인 결제 오류 응답 유지**다. 미검증 snapshot 영향까지 해결 완료로 기록하지 않는다.

## 8. PR #222 CI 실패와 snapshot 보완

[CI 실행 결과](https://github.com/attraction-final/attraction-reservation/actions/runs/42050577061/job/127325271187?pr=222)와 test-results artifact의 XML을 확인했다.

```text
전체: 1,911개 중 1개 실패
실패: RefundServiceConcurrencyTest.createRefund_concurrentRequests_NoOverRefund()
위치: RefundServiceConcurrencyTest.java:136
예상: unexpectedErrors가 비어 있음
실제: TICKET_NOT_REFUNDABLE 예외 4개
```

세 장 중 서로 겹치는 두 장씩 다섯 요청을 보내는 기존 테스트다. 수량 부족으로 거부될 요청이 `Ticket.refund()`까지 진행하여 이미 환불된 티켓이라는 이유로 거부됐다. 이 실패만으로 중복 환불 저장이나 재고 중복 복구까지 발생했다고 판단하지 않는다.

소유자 ID 사전 SELECT로 생성될 수 있는 이전 snapshot을 환불 수량 합계 조회도 사용하면, 앞선 요청이 커밋한 환불 수량을 놓칠 수 있다. 티켓 잠금 조회가 보는 현재 상태와 일반 수량 조회의 기준이 달라지는 것이 문제다.

### 보완 내용

- `RefundService`의 사용자 전체·부분, 관리자 전체·부분 환불 요청에 `@Transactional(isolation = Isolation.READ_COMMITTED)` 적용
- 일반 수량 SELECT가 실행되는 시점의 커밋 결과를 읽도록 변경
- 회원 → 결제 → 티켓 잠금 순서와 모든 환불 상태를 점유 수량에 포함하는 정책 유지
- 기존 동시성 테스트의 허용 예외를 늘려 실패를 숨기는 방식은 사용하지 않음

새 회귀 테스트 `createPartialRefund_ownerReadBeforeOtherCommit_usesLatestRefundQuantity()`는 사용자·관리자 부분 환불 두 케이스를 검증한다.

```text
후행 요청: 소유자 ID만 실제 조회하고 일시 정지
선행 요청: 같은 티켓 두 장을 실제 환불 요청하고 커밋
후행 요청: 회원·결제·티켓 잠금과 수량 조회 재개
기대: REFUND_INVALID_QUANTITY, 저장된 환불 1건·환불 티켓 2장
```

이 회귀 테스트는 DB 잠금 대기를 시간으로 추정하지 않고 사전 조회 → 다른 요청 커밋 → 후속 검증의 순서를 제어한다. 별도의 기존 테스트가 동시 요청과 회원 잠금 경합을 검증한다.

변경 후 테스트는 아직 실행하지 않았다. 다음 명령으로 기존 CI 실패와 추가 회귀를 함께 확인해야 한다.

```bash
./gradlew test --tests "*RefundServiceConcurrencyTest" --tests "*RefundWithdrawalConcurrencyIntegrationTest" --tests "*RefundServiceTest" --rerun-tasks
```

`READ_COMMITTED`는 해당 메서드가 새 트랜잭션을 시작할 때 적용된다. 이미 열린 외부 트랜잭션에 기본 REQUIRED로 참여하면 외부 격리 수준을 바꾸지 않는다. 현재 환불 Facade에는 외부 트랜잭션이 없으며, 앞으로 호출 구조를 변경할 때 이 조건을 유지하거나 별도로 검증해야 한다.
