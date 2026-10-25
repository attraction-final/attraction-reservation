# 결제 승인 (KAN-26, KAN-28, KAN-37, KAN-39)

PortOne V2 기반 결제 승인 기능의 구조, 설계 결정, 검증 결과, 남은 작업을 정리한 문서다.
웹훅 수신·처리는 [payment-webhook-plan.md](payment-webhook-plan.md), 금액 불일치 보상 취소(Outbox)는 [payment-compensation.md](payment-compensation.md)에 있다.

## 1. 개요

- 결제 승인은 **승인 API**(클라이언트 호출)와 **PG 웹훅**(PortOne 호출) 두 경로로 들어온다.
  - 승인 API는 `PaymentFacade.confirmPayment`, 웹훅은 Inbox 저장 후 스케줄러가 `PaymentFacade.syncPayment`로 처리한다.
  - 두 경로는 PG 조회 → 검증 → 원장 반영 로직(`PaymentFacade.reconcile`)을 함께 쓴다.
- 두 경로는 같은 결제에 대해 중복·동시에 들어올 수 있으므로, 원장 반영 로직(`PaymentCommandService.approvePayment`)은 **멱등**하고 **동시성에 안전**해야 한다.
- 결제 성공 여부의 기준은 **PG**(실제로 돈이 움직인 곳), 결제되어야 할 금액과 현재 상태의 기준은 **DB 원장**이다. 승인은 두 값을 비교하는 일이다.

## 2. 전체 흐름

```
[클라이언트]
  1. 결제 생성 → createPayment가 pgId 반환 (PortOne paymentId로 사용)
  2. PortOne.requestPayment({ storeId, channelKey, paymentId: pgId, totalAmount, ... })
  3. POST /api/payments/{pgId}/confirm?userId=...   body: { "orderId": ... }

[서버]
PaymentController
 └─ PaymentFacade.confirmPayment(userId, pgId, request)     ← 트랜잭션 없음
     ├─ PaymentService.getOwnedPayment(userId, pgId)        ← 읽기 전용, 남의 결제·없는 결제면 404
     ├─ orderId 불일치                → INVALID_INPUT_VALUE (400)
     └─ reconcile(payment)                                  ← 승인 API·웹훅 공통
          ├─ 이미 PAID                → 현재 결제 정보 반환 (PG 호출 없음)
          ├─ 이미 FAILED              → PAYMENT_ALREADY_PROCESSED (409)
          ├─ PaymentGateway.getPayment(pgId)                ← PortOne 조회 (트랜잭션 밖)
          │    ├─ PG에 결제 없음       → PAYMENT_PG_PAYMENT_NOT_FOUND (404)
          │    ├─ PG FAILED           → failPayment → PAYMENT_PG_FAILED (502)
          │    └─ PG 미완료(READY 등)  → PAYMENT_NOT_PAID (409)
          ├─ 금액 불일치
          │    ├─ failPaymentWithCompensation이 true  → 결제 FAILED·주문 취소·보상 요청 함께 커밋
          │    │                                        (원장 CANCELLED면 실패 처리 없이 보상 요청만)
          │    │                                        → PaymentCompensationProcessor 즉시 1회 시도 (실패해도 스케줄러 재시도)
          │    ├─ failPaymentWithCompensation이 false → 다른 요청이 이미 처리, 보상 요청 생략
          │    └─ PAYMENT_INCORRECT_AMOUNT (400)
          └─ PaymentCommandService.approvePayment(pgId, paidAt, pgAmount)  ← @Transactional, 비관적 락
               ├─ 이미 PAID           → 현재 결제 정보 반환 (멱등)
               ├─ FAILED              → PAYMENT_ALREADY_PROCESSED (409)
               ├─ CANCELLED           → 같은 트랜잭션에서 보상 요청 저장 후 CANCELLED 응답
               │                        → (Facade) PaymentCompensationProcessor 즉시 1회 시도
               │                        → PAYMENT_ALREADY_CANCELLED (409)
               ├─ 그 밖의 전이 불가    → PAYMENT_INVALID_STATUS_TRANSITION (409)
               └─ 정상                → 결제 PAID(paidAt = PG 결제 시각) + 주문 PAID

[웹훅]  상세는 payment-webhook-plan.md
POST /api/payments/webhooks/portone → 서명 검증 → webhook_events 저장 → 200
WebhookEventScheduler → WebhookEventProcessor
 └─ PaymentFacade.syncPayment(pgId)                         ← 소유자 확인 없음
     ├─ 원장에 없는 pgId              → NOT_OUR_PAYMENT
     ├─ 이미 PAID                     → ALREADY_DONE
     └─ reconcile(payment)의 결과·예외를 PaymentSyncResult로 변환
        (재시도가 필요한 오류만 예외로 전파)
```

## 3. 패키지 구조와 파일

```text
domain/payment
├── controller/PaymentController          POST /api/payments/{pgId}/confirm
├── dto/ConfirmPaymentRequest             orderId
├── dto/ConfirmPaymentResponse            id, orderId, pgId, amount, status, paidAt
├── dto/PaymentSyncResult                 syncPayment 결과
├── facade/PaymentFacade                  PG 호출과 트랜잭션 조율, 금액 검증, 보상 요청
│                                         confirmPayment(승인 API), syncPayment(웹훅)
├── facade/WebhookEventProcessor          웹훅 처리 (payment-webhook-plan.md)
├── facade/PaymentCompensationProcessor   보상 취소 처리 (payment-compensation.md)
├── service/PaymentService                createPayment, cancelledByUser, getOwnedPayment, findByPgId
├── service/PaymentCommandService         approvePayment, failPayment, failPaymentWithCompensation (비관적 락)
├── service/WebhookEventCommandService    웹훅 저장·점유·결과 기록
├── service/PaymentCompensationService    보상 요청 저장·점유·결과 기록
├── repository/PaymentRepository          findWithLockByOrderId, findWithLockByPgId,
│                                         findByPgId, findByPgIdAndUserId
├── repository/WebhookEventRepository, PaymentCompensationRepository
├── entity/WebhookEvent, enums/WebhookStatus
├── entity/PaymentCompensation, enums/CompensationStatus
├── scheduler/WebhookEventScheduler, PaymentCompensationScheduler
├── port/PaymentGateway                   PG 연동 인터페이스 (domain 소유)
├── port/dto/PaymentGatewayResponse       pgId, status, totalAmount(Long), paidAt(Instant)
├── port/dto/PaymentCancellationResponse
└── port/enums/GatewayPaymentStatus, GatewayCancellationStatus

domain/order
├── entity/Order                          markAsPaid, markAsCancelled
└── entity/enums/OrderStatus              canTransitTo 상태 전이 규칙

infra/portone
├── client/PortOneClient                  PaymentGateway 구현 (RestClient)
├── config/PortOneConfig, PortOneProperties
├── controller/PortOneConfigController    GET /api/config/portone (storeId, channelKey)
├── dto/                                  PortOne 요청·응답·오류 DTO
└── webhook/                              PortOneWebhookController, PortOneWebhookVerifier

global/config/SchedulingConfig            @EnableScheduling
```

### 의존 방향

- `domain → infra` 참조 없음. 포트(`PaymentGateway`)를 domain에 두고 infra가 구현한다.
- `infra → domain`은 port, port.dto, port.enums만 참조한다. PortOne 응답 DTO는 domain으로 새지 않는다.
- `payment → order`는 `Payment.order` 연관관계로 주문 상태를 같은 트랜잭션에서 변경한다.

## 4. 주요 설계 결정

### 4.1 PG 호출과 트랜잭션 분리

- PG 호출은 `PaymentFacade`(트랜잭션 없음)에서, DB 반영은 `PaymentCommandService`(`@Transactional`)에서 한다.
- Facade에 `@Transactional`을 붙이면 PG 응답을 기다리는 동안 DB 커넥션과 결제 락을 계속 잡게 된다.
- 같은 클래스 안에서 호출하면 프록시를 거치지 않아 `@Transactional`이 적용되지 않으므로 클래스를 분리했다.
- 금액 불일치 보상은 "원장 FAILED + 보상 요청 커밋 → PG 취소" 순서다. PG 취소를 트랜잭션 안에서 하지 않는다(Outbox, 4.8).

### 4.2 검증 위치: Facade의 사전 검사와 락 안의 재확인

- Facade는 락 없이 조회한 결제(승인 API는 `getOwnedPayment`, 웹훅은 `findByPgId`)로 PAID·FAILED 여부와 금액을 먼저 검사해 불필요한 PG 호출을 줄인다.
- 상태는 조회 이후 바뀔 수 있으므로(TOCTOU), 실제 변경은 `approvePayment`·`failPayment`가 **락을 잡고 상태를 다시 확인한 뒤** 한다.
- 금액(`payments.amount`)은 생성 후 바뀌지 않으므로 락 밖에서 비교해도 된다.

### 4.3 멱등성

- `approvePayment`: 이미 `PAID`인 결제는 예외 없이 현재 결제 정보를 반환한다. 먼저 도착한 요청이 처리한 것으로 간주한다.
  - 웹훅 처리에서는 `ALREADY_DONE`으로 끝나 재시도하지 않는다.
- `failPayment`·`failPaymentWithCompensation`: `PENDING`일 때만 실패 처리하고 `true`를 반환한다. 이미 처리된 결제면 상태를 바꾸지 않고 `false`를 반환한다.
  - 금액 불일치 요청이 동시에 들어와도 `true`를 받은 한 요청만 보상 요청을 만든다.
  - 예외: `failPaymentWithCompensation`은 원장이 `CANCELLED`이고 보상 요청이 없으면 실패 처리 없이 보상 요청만 저장하고 `true`를 반환한다.
- 로컬 `CANCELLED` 결제가 PG에서 결제 완료되면 PG 결제를 보상 취소한다(Outbox).
  - PG 결제창이 열려 있는 동안 사용자가 취소(`cancelledByUser`)하고, 이후 PG에서 결제를 마치면 원장 CANCELLED + PG PAID 조합이 생긴다.
  - `approvePayment`가 락 안에서 `CANCELLED`를 확인하면, **같은 트랜잭션에서 보상 요청을 저장**하고 `CANCELLED` 상태의 결제 정보를 반환한다. Facade가 락 없이 `PENDING`을 읽은 직후 취소가 커밋된 경우도 락 안에서 걸러진다.
  - 원장 확인과 보상 요청이 함께 커밋되므로, 그 사이에 서버가 종료돼 보상 요청이 사라지는 경우가 없다. 보상 요청 저장에 실패하면 예외가 전파되고 트랜잭션이 롤백되어 다음 요청(승인 API 재호출·웹훅 재처리)에서 다시 판단한다.
    - 이전에는 예외로 롤백한 뒤 별도 트랜잭션(`requestCompensationForCancelled`)에서 저장해, 두 트랜잭션 사이에 승인 API 스레드가 종료되면 결제 완료 웹훅에만 복구를 의존했다.
  - Facade는 `CANCELLED` 응답을 받으면 보상 Processor로 즉시 1회 시도한 뒤 `PAYMENT_ALREADY_CANCELLED`를 던진다. 보상 요청이 이미 있던 경우에도 즉시 시도를 호출하지만, Processor가 점유 시 상태를 다시 확인해 완료·처리 중인 요청은 건너뛴다.
  - 결제당 보상 요청은 하나(`pg_id` UNIQUE)라, 이미 있으면 저장하지 않는다. 결제 락 안에서 확인하므로 동시에 들어와도 1건이다.
  - 웹훅 처리에서는 `COMPENSATED`로 받아 웹훅을 `PROCESSED`로 기록한다. 승인 API는 `PAYMENT_ALREADY_CANCELLED`(409)로 응답한다.

### 4.4 동시성 제어: 낙관적 락 → 비관적 락 (KAN-22)

- `@Version`을 제거하고 `@Lock(PESSIMISTIC_WRITE)` 조회를 사용한다.
- **같은 결제를 변경하는 모든 경로(승인, 실패 처리, 사용자 취소)가 락 조회를 거쳐야 한다.** 락 없이 조회한 뒤 변경하는 경로가 있으면 lost update가 생긴다.
- `@EntityGraph`로 order(·user)를 함께 조회해도 Hibernate 7은 `for update of p1_0`을 생성해 **payments 행만 잠근다** (MySQL 컨테이너에서 확인).
- 락 대기 타임아웃은 Hikari `connection-init-sql`로 설정한다 (4.5 참고).
- 락 획득 실패(`PessimisticLockingFailureException`)는 `CONCURRENT_UPDATE_CONFLICT`(409)로 응답한다.

### 4.5 MySQL 락 타임아웃: QueryHint는 무시된다

MySQL 9.6 + Hibernate 7.4.5 컨테이너에서 확인한 결과:

| 설정 | 결과 | 소요 |
|---|---|---|
| `@QueryHints(jakarta.persistence.lock.timeout = 3000)`만 사용 | 락 해제까지 대기 후 획득 | 6009ms |
| `SET SESSION innodb_lock_wait_timeout = 3` 추가 | `CannotAcquireLockException` | 3015ms |

- MySQL `FOR UPDATE`에는 대기 시간 지정 문법이 없어 힌트가 SQL에 반영되지 않는다(기본 50초).
- 적용 방법:
  - `application.yaml`: `spring.datasource.hikari.connection-init-sql: SET SESSION innodb_lock_wait_timeout = 3`
  - `application-test.yml`(H2): `SET LOCK_TIMEOUT 3000`으로 덮어쓴다. MySQL 전용 SQL이 H2에서 실패하기 때문.

### 4.6 결제·주문 결합도

- 승인·실패 시 주문은 상태만 바꾸므로 별도 서비스 계층 대신 `payment.getOrder().markAsPaid()` / `markAsCancelled()`로 같은 트랜잭션에서 변경한다.
- 계층을 추가해도 `Payment → Order` 엔티티 연관관계가 남아 있는 한 결합도는 줄지 않는다.
- 주문 쪽 로직이 늘어나면(좌석 확정, 재고 복구, 알림 등) `OrderService` 호출이나 도메인 이벤트(동기 `@EventListener`, 같은 트랜잭션)로 옮긴다.
  - `@TransactionalEventListener(AFTER_COMMIT)`는 결제 PAID·주문 PENDING 불일치를 만들 수 있으므로 쓰지 않는다.
- 주문 상태 규칙은 `Order`가 가진다 (`OrderStatus.canTransitTo`).
- 참고: dirty checking은 cascade와 무관하다. 영속성 컨텍스트가 관리하는 엔티티면 연관 엔티티도 커밋 시 UPDATE된다.

### 4.7 결제 식별과 소유자 확인

- `createPayment`가 `pgId`(UUID)를 반환한다. 클라이언트는 이를 PortOne `paymentId`로 쓰고, 승인 API 경로에도 사용한다.
  - DB `payments.id`(순차 증가)를 노출하지 않고, 웹훅과 같은 식별자를 쓰기 위해 `pgId`로 통일했다.
  - `pgId`는 경로에서만 받는다. 요청 본문에는 `orderId`만 있고, 결제의 주문과 다르면 400으로 거절한다.
- 소유자 확인(`getOwnedPayment`)은 승인 API 전용이므로 **Facade에서 PG 호출 전에** 한다.
  - 웹훅에는 로그인 사용자가 없으므로 `reconcile`·`approvePayment`는 `userId` 없이 공통 로직으로 유지한다. 웹훅은 `findByPgId`로 소유자 확인 없이 조회한다(서명 검증으로 출처를 보장).
  - 결제 주인은 바뀌지 않으므로 락 없이 확인해도 TOCTOU 문제가 없다.
  - 남의 결제와 없는 결제 모두 `PAYMENT_NOT_FOUND`(404)로 응답해 존재 여부를 숨긴다.
- 금액은 클라이언트에서 받지 않는다. PG 조회 결과와 원장만 비교한다.

### 4.8 보상 처리 (금액 불일치, 원장 취소)

- PG에서는 결제됐지만 금액이 원장과 다르면 원장에 반영하지 않고 PG 결제를 취소한다. **Outbox 패턴**으로 구현했다(KAN-39, 상세는 [payment-compensation.md](payment-compensation.md)).
- 순서: `failPaymentWithCompensation`(결제 FAILED + 주문 취소 + 보상 요청, 한 트랜잭션) → 커밋 후 `PaymentCompensationProcessor`가 즉시 1회 PG 전체 취소 → `PAYMENT_INCORRECT_AMOUNT`(400) 응답.
  - 원장이 `CANCELLED`면 결제 실패 처리 없이 보상 요청만 저장한다(사유 "결제 금액 불일치").
- 원장 `CANCELLED` + PG `PAID`(금액 일치)는 4.3과 같이 `approvePayment`가 같은 트랜잭션에서 보상 요청을 저장한다(사유 "취소된 주문의 결제").
- 즉시 시도가 실패해도 보상 요청이 남아 스케줄러가 재시도하므로, **응답은 PG 취소 결과와 무관하다.** 웹훅은 `COMPENSATED`로 받아 웹훅을 `PROCESSED`로 기록한다.
- 보상 요청은 아래 두 경우에 `DEAD`가 되고 error 로그가 남는다. 둘 다 수동 처리 대상이다.
  - 5번째 취소 시도까지 실패한 경우: PG 취소가 거듭 실패한 것이므로 수동 환불한다.
  - 취소 결과 확인 대기(`PG_PENDING`)가 24시간 안에 확정되지 않은 경우: 취소 요청은 접수됐을 수 있으므로 PG에서 취소 반영 여부를 먼저 확인한 뒤 수동 환불한다(중복 환불 방지).

### 4.9 PortOne 연동 (`PortOneClient`)

- **응답 검증은 infra에서 한다.** 상태가 PAID인데 `amount.total`이나 `paidAt`이 없으면 경고 로그를 남기고 `PAYMENT_GATEWAY_ERROR`(502).
  - domain은 항상 유효한 값만 받는다. `Payment.markAsPaid`의 `requireNonNull`은 정상 흐름에서는 실행되지 않는 마지막 방어선이다.
- 반환하는 `pgId`는 응답의 `id`가 아니라 요청한 값을 그대로 쓴다.
- PortOne 상태 문자열은 `GatewayPaymentStatus`로 변환하고, 지원하지 않는 값(예: `VIRTUAL_ACCOUNT_ISSUED`)이나 null은 `UNKNOWN`으로 바꾼다. Facade에서는 `PAYMENT_NOT_PAID`(409)가 된다.
- 조회 시 404 응답은 모두 "PG에 결제 없음"(`Optional.empty()`)으로 처리한다.
- 취소
  - 오류 응답을 `PortOneErrorResponse`로 파싱해 `type`이 `PAYMENT_ALREADY_CANCELLED`면 성공 처리한다 (보상 목적이 이미 달성됨).
  - 통신 실패(`ResourceAccessException`)는 같은 멱등 키로 최대 3회 재시도한다. 모두 실패하면 `PAYMENT_CANCELLATION_FAILED`(502).
  - 멱등 키는 `cancelPayment` 호출마다 새로 만든다(`UUID.randomUUID()`). 한 호출 안의 재시도에서만 같은 키를 쓴다.
    - PortOne은 같은 키의 응답(실패 포함)을 3시간 동안 저장해 돌려준다. 호출 간에 키를 고정하면 실패한 취소를 다시 시도해도 저장된 실패 응답만 받으므로 고정하지 않는다.
    - 호출이 겹쳐 같은 결제를 두 번 전체 취소해도 두 번째는 `PAYMENT_ALREADY_CANCELLED`로 성공 처리된다.
- 타임아웃: 연결 5초, 읽기 10초. 조회와 취소가 같은 `RestClient`를 쓰므로 취소에도 10초가 적용된다. 취소가 읽기 타임아웃되면 같은 멱등 키로 재시도하므로 중복 취소되지 않는다.
- 설정: `application.yaml`의 `portone.*`는 환경변수로 주입하고, `PortOneProperties`의 `@Validated` + `@NotBlank`로 누락 시 시작 시점에 실패한다.
  - 필요한 환경변수: `PORTONE_API_SECRET`, `PORTONE_STORE_ID`, `PORTONE_CHANNEL_KEY`, `PORTONE_WEBHOOK_SECRET` (`PORTONE_BASE_URL`은 기본값 있음)
  - 테스트: `src/test/resources/config/application.yaml`에 가짜 값 (프로필과 무관하게 적용)

### 4.10 포트 계약 (`PaymentGateway`)

Facade와 `approvePayment`가 의존하는 전제다. 구현체가 바뀌어도 지켜져야 하며, `PaymentGateway` Javadoc에도 같은 내용이 있다.

- `getPayment`
  - 반환값의 `pgId`는 요청한 `pgId`와 같다.
  - 상태가 `PAID`면 `totalAmount`와 `paidAt`은 null이 아니다. 누락된 응답은 반환하지 않고 `PAYMENT_GATEWAY_ERROR`로 처리한다.
  - 지원하지 않는 상태는 `UNKNOWN`으로 변환한다.
  - PG에 결제가 없으면 빈 값을 반환한다.
- `cancelPayment`
  - `amount`가 null이면 전체 취소한다.
  - 전체 취소에서 이미 취소된 결제면 성공으로 간주한다. 같은 결제를 여러 번 전체 취소해도 한 번만 환불된다(보상 재시도의 근거).
  - 부분 취소(`amount`가 있음)에서 이미 취소된 결제면 성공으로 보지 않고 `PAYMENT_ALREADY_CANCELLED`를 던진다. 다른 경로로 전체 취소된 것이라 이번 취소가 반영됐다고 볼 수 없다(KAN-51).
  - `currentCancellableAmount`(4인자 메서드, KAN-51): 이번 취소 전에 PG에 남아 있어야 할 취소 가능 금액. PG 값과 다르면 PG가 거절하고 `PAYMENT_CANCELLABLE_AMOUNT_MISMATCH`를 던진다. 앞선 취소가 이미 반영됐을 수 있어 재시도하면 안 된다. 기존 3인자 호출(보상 취소)은 이 값을 보내지 않는다.
  - 그 밖에 취소 여부를 확인할 수 없으면 `PAYMENT_CANCELLATION_FAILED`를 던진다.
- 금액 비교는 `int`(원장)와 `Long`(PG)의 숫자 비교다. `Objects.equals`는 타입이 달라 항상 false이므로 쓰지 않는다.

## 5. ErrorCode

| 코드 | 상태 | 상황 |
|---|---|---|
| `INVALID_INPUT_VALUE` | 400 | 요청 `orderId`가 결제의 주문과 다름 |
| `PAYMENT_NOT_FOUND` | 404 | 결제 없음, 다른 사용자의 결제 |
| `PAYMENT_PG_PAYMENT_NOT_FOUND` | 404 | PG에 결제 없음 |
| `PAYMENT_NOT_PAID` | 409 | PG에서 결제 완료 상태가 아님 (READY, PENDING, UNKNOWN 등) |
| `PAYMENT_ALREADY_PROCESSED` | 409 | 이미 실패 처리된 결제 |
| `PAYMENT_ALREADY_CANCELLED` | 409 | 사용자가 취소한 결제가 PG에서 결제 완료됨 (보상 요청 저장 후 PG 취소 시도). 부분 취소 대상 결제가 PG에서 이미 취소됨(환불, 내부 처리용) |
| `PAYMENT_CANCELLABLE_AMOUNT_MISMATCH` | 409 | PG 취소 가능 금액이 요청한 값과 다름 (환불, 내부 처리용) |
| `PAYMENT_INCORRECT_AMOUNT` | 400 | PG 금액과 원장 금액 불일치 (보상 요청 저장 후 PG 취소 시도) |
| `PAYMENT_INVALID_STATUS_TRANSITION` | 409 | 결제 상태 전이 불가 |
| `ORDER_INVALID_STATUS_TRANSITION` | 409 | 주문 상태 전이 불가 |
| `PAYMENT_PG_FAILED` | 502 | PG에서 결제 실패 |
| `PAYMENT_GATEWAY_ERROR` | 502 | PortOne 조회 실패, 응답 필수값 누락 |
| `PAYMENT_CANCELLATION_FAILED` | 502 | PortOne 취소 실패 (보상 처리에서는 응답으로 나가지 않고 실패로 기록·재시도) |
| `CONCURRENT_UPDATE_CONFLICT` | 409 | 비관적 락 획득 실패 |
| `PAYMENT_WEBHOOK_INVALID_SIGNATURE` | 401 | 웹훅 헤더 누락, 서명·타임스탬프 검증 실패 |
| `PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION` | 409 | 웹훅 처리 상태 전이 불가 (내부용) |
| `PAYMENT_WEBHOOK_NOT_FOUND` | 404 | 웹훅 수신 기록 없음 (내부용) |
| `PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION` | 409 | 보상 처리 상태 전이 불가 (내부용) |
| `PAYMENT_COMPENSATION_NOT_FOUND` | 404 | 보상 요청 없음 (내부용) |

상태 전이 오류는 프로젝트 규칙("409: 현재 상태와 충돌하는 요청")에 맞춰 400에서 409로 변경했다.

## 6. 테스트

| 테스트 | 종류 | 내용 |
|---|---|---|
| `PaymentFacadeTest` | Mockito | 정상 승인, 남의 결제·orderId 불일치·이미 PAID·이미 FAILED면 PG 미호출, PG 결제 없음, PG 실패 시 실패 처리, PG 미완료 상태별 `PAYMENT_NOT_PAID`, 금액 불일치 시 보상 요청·즉시 시도·이미 처리돼 생략, 원장 CANCELLED + PG PAID면 즉시 시도 후 `PAYMENT_ALREADY_CANCELLED`·PG 금액을 보상 금액으로 전달, `syncPayment` 결과별(원장 CANCELLED 보상 포함)·상태 충돌 시 보상 없음·재시도 필요 오류 전파 |
| `PaymentCommandServiceTest` | Mockito | 승인 성공·멱등·FAILED 거절·결제 없음, CANCELLED면 같은 트랜잭션에서 보상 요청 저장 후 CANCELLED 반환·이미 요청됨·취소 아님·보상 저장 실패 시 예외 전파, 실패 처리 성공·PENDING 아니면 건너뜀·결제 없음, 금액 불일치 시 보상 요청 저장(원장 CANCELLED면 실패 처리 없이 저장) |
| `PaymentServiceTest` | Mockito | 결제 생성, 사용자 취소, 소유자 결제 조회 |
| `PaymentServiceConcurrencyTest` | Testcontainers MySQL | 승인 락 대기 후 skip, 동시 승인 10건, 취소 락 대기·타임아웃, 동시 취소 10건, 취소 커밋 후 승인은 CANCELLED 응답 + 보상 요청 1건 |
| `PaymentControllerTest` | `@WebMvcTest` | 200(응답 데이터) / 404 / 502 / userId 누락 400 / orderId 누락 400, `ApiResponse` 형식 |
| `PaymentRepositoryTest` | `@DataJpaTest` + H2 | `findByPgIdAndUserId` 쿼리 동작 (본인·다른 사용자·없는 pgId) |
| `PortOneClientTest` | `MockRestServiceServer` | 응답 변환·필수값 검증·지원하지 않는 상태, 404 처리, 호출마다 새 멱등 키·호출 안 재시도는 같은 키, 이미 취소됨 처리(전체 취소는 성공, 부분 취소는 예외), 취소 가능 금액 전송·불일치 예외, 통신 실패 재시도 |
| `OrderTest`, `PaymentTest` | 단위 | 상태 전이, `paidAt` null 검증 |

웹훅 관련 테스트는 [payment-webhook-plan.md 6장](payment-webhook-plan.md#6-테스트), 보상 관련 테스트는 [payment-compensation.md 7장](payment-compensation.md#7-테스트)에 있다.

### 테스트 환경 메모

- `application-test.yml`은 H2를 사용한다. 락 동작은 DB마다 다르므로 동시성 테스트는 `@Import(TestcontainersConfiguration.class)`로 MySQL 컨테이너에서 실행한다. **Docker가 실행 중이어야 한다.**
  - test 프로필 없이 `@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")`를 사용한다. test 프로필의 H2용 init SQL이 MySQL에서 실패하기 때문.
- `@DataJpaTest`는 `JpaConfig`(JPA Auditing)를 로드하지 않으므로 `@Import(JpaConfig.class)`가 필요하다.
- `PortOneClientTest`의 `@Disabled` 1건은 아래 남은 작업(404 type 확인)을 구현할 때 활성화한다.

## 7. 남은 작업

### 운영 배포 전 필수

1. **인증 연동**: 지금은 `userId`를 `@RequestParam`으로 받아 소유자 확인을 우회할 수 있다. `@AuthenticationPrincipal` 등으로 교체 전까지 운영 노출 금지.
2. **웹훅 배포 준비**: `webhook_events` 테이블 생성, PortOne 콘솔에 웹훅 URL 등록, 인증 적용 시 웹훅 경로 `permitAll`. 상세는 [payment-webhook-plan.md 7장](payment-webhook-plan.md#7-남은-작업).
3. **보상 배포 준비**: `payment_compensations` 테이블 생성, `DEAD` 보상 요청 확인·수동 환불 절차. 상세는 [payment-compensation.md 8장](payment-compensation.md#8-남은-작업).
4. **재고 복구**: `cancelledByUser`, `failPayment`에 TODO로 남아 있다.

### 개선 항목

- 조회 404 응답의 `type`이 `PAYMENT_NOT_FOUND`인지 확인. 지금은 경로 오류 등 다른 404도 "결제 없음"으로 처리한다.
- `PortOneConfig`: `RestClient`를 타입으로만 주입하고 정적 `RestClient.builder()`로 생성 → Boot의 `RestClient.Builder` 주입으로 변경 권장.
- `io.portone:server-sdk` 의존성은 웹훅 서명 검증(`WebhookVerifier`)에만 사용한다. 결제 조회·취소는 `RestClient`로 직접 호출한다.
- PG 연동 컨트롤러(`PortOneConfigController`, `PortOneWebhookController`)는 infra에 둔다. `architecture.md`에 이 기준을 추가할지 팀과 정한다.
- `PAYMENT_INVALID_STATUS_TRANSITION`과 `ORDER_INVALID_STATUS_TRANSITION` 메시지가 같아 구분 불가.
- 오류 코드 검토: `PAYMENT_INCORRECT_AMOUNT`(400 → 409?), `PAYMENT_PG_FAILED`(사용자 사유 실패도 502).
- 이름 규칙: `cancelledByUser` → `cancelPaymentByUser`, `getOwnedPayment` → `findOwnedPayment`, `findByPgIdAndUserId` → 파생 쿼리 `findByPgIdAndOrderUserId`.
- 결제 준비 API 또는 주문 생성 응답에 `paymentId`, `amount`, `orderName` 포함 (`createPayment` 호출처 미정).

### PortOne 문서로 확인 필요

- `Idempotency-Key` 헤더 값 형식(따옴표로 감싸는지)
- 결제 상태 목록(`VIRTUAL_ACCOUNT_ISSUED` 등)과 각 상태에서의 승인 처리 방침
- 같은 멱등 키로 동시에 요청했을 때의 응답(409 "처리 중" 여부)
- 웹훅 재전송 횟수·기간

### PortOne 문서로 확인한 것

- `Idempotency-Key`: 멱등성을 보장하고 같은 키의 응답을 3시간 동안 저장한다 → 호출마다 새 키를 쓴다(4.9).
- 결제 취소 오류의 `type` 값 (server-sdk 0.24.0 직렬화 이름으로 확인): 이미 취소됨 `PAYMENT_ALREADY_CANCELLED`, 취소 가능 금액 불일치 `CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN`.
- 부분 취소의 `currentCancellableAmount`로 중복 취소를 막는 방식은 KAN-51(환불)에서 구현했다. 상세는 [refund-requirements.md 5-2](../requirements/refund-requirements.md).

## 8. 보상 처리의 Outbox 방식 (KAN-39)

구현 상세는 [payment-compensation.md](payment-compensation.md)에 있다. 요약:

```
failPaymentWithCompensation (트랜잭션 1개): 결제 FAILED + 주문 취소 + 보상 요청(payment_compensations) INSERT → 커밋
approvePayment (트랜잭션 1개): 원장 CANCELLED + PG PAID면 보상 요청만 INSERT → 커밋 (4.3)
  → PaymentCompensationProcessor가 즉시 1회 PG 전체 취소
  → 실패하거나 시도 전 서버 종료 시 PaymentCompensationScheduler(30초)가 재시도 (1·2·4·8분 백오프, 5회 실패 시 DEAD)
```

- 보상 판단과 보상 요청 기록이 함께 커밋되므로, 커밋된 보상은 반드시 PG 취소가 시도된다.
- 같은 결제를 여러 번 전체 취소해도 두 번째부터 "이미 취소됨"으로 성공 처리되어 중복 환불되지 않는다. 멱등 키는 호출마다 새로 만든다(4.9).
- 웹훅 수신은 Inbox(들어오는 메시지를 잃지 않음), 보상 취소는 Outbox(우리가 결정한 외부 호출을 잃지 않음)다.
- 승인 단계에 한 번도 도달하지 못한 결제(웹훅 미수신 + 승인 API 미호출)는 Inbox·Outbox로도 잡히지 않는다 → **대사 배치**(PortOne 결제 목록과 원장 비교)를 마지막 안전망으로 둔다.
