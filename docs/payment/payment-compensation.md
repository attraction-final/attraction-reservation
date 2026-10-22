# 결제 보상 취소 Outbox (KAN-39)

결제 승인 중 PG 결제 금액이 원장과 다르거나, 사용자가 취소한 결제가 PG에서 결제 완료됐을 때 PG 결제를 취소(보상)하는 기능을 Outbox 패턴으로 구현한 구조와 남은 작업을 정리한 문서다.
결제 승인 구조는 [payment-approval.md](payment-approval.md), 웹훅 처리 구조는 [payment-webhook-plan.md](payment-webhook-plan.md)를 참고한다.

## 1. 문제와 방향

### 이전 구조의 문제

```
failPayment (트랜잭션: 결제 FAILED + 주문 취소) → 커밋 → PG 취소 호출
```

- DB 커밋과 PG 취소를 하나의 트랜잭션으로 묶을 수 없다(dual write).
- 커밋 후 PG 취소가 실패하거나 그 사이 서버가 종료되면 **원장 FAILED, PG PAID(돈은 결제됨)** 로 남는다.
- 이후 요청은 결제가 이미 FAILED라 `PAYMENT_ALREADY_PROCESSED`로 끝나 보상이 다시 시도되지 않는다.

### 방향: 보상 판단과 보상 요청 기록을 같은 트랜잭션에서 커밋

```
failPaymentWithCompensation (트랜잭션 1개): 결제 FAILED + 주문 취소 + 보상 요청 INSERT → 커밋
  → 즉시 PG 취소 1회 시도 (대부분 여기서 완료)
  → 실패하거나 시도 전 서버 종료 시 스케줄러가 이어받아 재시도
```

- 커밋된 보상 요청은 즉시 시도나 스케줄러 재시도로 **반드시 PG 취소가 시도된다.**
- 보상 요청 저장에 실패하면 결제 실패 처리도 함께 롤백된다.

| Outbox 패턴 | 이번 구조 |
|---|---|
| 업무 변경과 보낼 메시지를 같은 트랜잭션에 저장 | 결제 FAILED·주문 취소와 보상 요청을 같은 트랜잭션에 저장 |
| relay가 메시지를 외부로 전달 | Processor(즉시 시도)·스케줄러(재시도)가 PortOne 취소 API 호출 |
| at-least-once, 받는 쪽 멱등 | 같은 결제를 여러 번 취소해도 두 번째부터 "이미 취소됨"으로 성공 처리 |

- 웹훅 수신(`webhook_events`)은 **Inbox**(들어오는 메시지를 잃지 않음), 보상 취소(`payment_compensations`)는 **Outbox**(우리가 결정한 외부 호출을 잃지 않음)다. 두 패턴은 방향이 반대이고 함께 쓴다.

## 2. 구성

```text
domain/payment
├── entity/PaymentCompensation              보상 요청(Outbox) 엔티티, 상태 전이·재시도 백오프
├── entity/enums/CompensationStatus         REQUESTED, PROCESSING, FAILED, DONE, DEAD
├── repository/PaymentCompensationRepository    findByPgId, findWithLockById, findProcessableIds
├── service/PaymentCommandService           failPaymentWithCompensation: 결제 실패 + 보상 요청 저장 (같은 트랜잭션)
│                                           requestCompensationForCancelled: 취소된 결제의 보상 요청 저장
├── service/PaymentCompensationService      저장, 처리 대상 조회, 행 잠금 후 점유, 완료·실패 기록
├── dto/ClaimedCompensation                 점유한 요청에서 트랜잭션 밖 PG 취소·로그에 쓰는 값
├── facade/PaymentCompensationProcessor     점유 → PG 전체 취소 → 결과 기록 (트랜잭션 없음)
├── facade/PaymentFacade                    금액 불일치·취소된 결제의 PG 결제 완료 시 보상 요청 후 즉시 1회 시도
└── scheduler/PaymentCompensationScheduler  payment.compensation.scheduler.enabled로 켜고 끔
```

- 보상은 결제 실패 처리와 같은 트랜잭션에서 생성되고 결제 포트(`PaymentGateway`)를 호출하므로 `domain/payment`에 둔다.
- 범용 `outbox_events` 대신 보상 전용 테이블을 쓴다. Outbox로 보낼 이벤트가 보상 취소 하나뿐이기 때문이다. 다른 외부 호출(부분 환불 등)이 생기면 범용화를 검토한다.

## 3. 저장 (`payment_compensations`)

| 컬럼 | 설명 |
|---|---|
| `id` | PK (IDENTITY) |
| `pg_id` | 결제 pgId(40자). **UNIQUE**, 결제당 보상 1건 |
| `amount` | PG가 실제 결제한 금액(bigint). 원장 금액과 다를 수 있다. 감사·로그·수동 환불용 |
| `reason` | PG 취소 사유(100자). 재시도 때도 같은 값을 보낸다 |
| `status` | `CompensationStatus` |
| `attempt_count` | 처리 시도 횟수 |
| `next_retry_at` | 다음 재시도 시각(NOT NULL). 생성 시 요청 시각. `REQUESTED`는 이 값과 무관하게 바로 처리 대상 |
| `last_error` | 마지막 실패 사유(500자에서 자름) |
| `processed_at` | DONE 시각, 처리 전엔 null |
| `created_at`, `updated_at` | `BaseTimeEntity`. 요청 시각, 처리 중 멈춘 행 판단 기준 |

- 인덱스: `(status, next_retry_at)`
- `ddl-auto: validate`이므로 **테이블은 직접 만들어야 한다**(7장).
- `pg_id`를 PK로 쓰지 않는다. 직접 정한 ID는 Spring Data `save()`가 merge(SELECT 후 INSERT)로 처리하고, 결제당 1건 규칙은 UNIQUE로 충분하다.

### 상태 전이 (`CompensationStatus.canTransitTo`)

```
생성 → REQUESTED ──→ PROCESSING ──→ DONE
                       ▲  │  └───→ DEAD    (최대 시도 횟수 초과)
         FAILED ───────┘  │
           ▲              │
           └──────────────┘  (PG 취소 실패, 백오프 후 재시도)
         PROCESSING → PROCESSING (멈춘 처리 회수)
```

- 끝난 상태(`DONE`, `DEAD`)에서는 어떤 전이도 허용하지 않는다(`PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION`, 409).
- 처리 가능 판단(`PaymentCompensation.isProcessable`)
  - `REQUESTED` (시각과 무관. DB가 시각을 반올림해 저장하면 생성 직후 값이 `now`보다 미래가 되어 즉시 시도가 건너뛰어질 수 있기 때문)
  - `FAILED`이고 `next_retry_at <= now`
  - `PROCESSING`이고 `updated_at`이 5분 이상 지남 (처리 중 서버 종료)
- 재시도 백오프: 1분, 2분, 4분, 8분. 5번째 실패에서 `DEAD` + error 로그("수동 환불 필요").
- 생성 시 금액이 null이거나 0 이하면 `PAYMENT_INVALID_AMOUNT`.

## 4. 보상 요청 생성

### 금액 불일치 (`failPaymentWithCompensation`)

- 결제를 비관적 락으로 조회해 `PENDING`일 때만 결제 FAILED·주문 취소를 하고, 이어서 보상 요청을 저장한다.
  - 보상 저장(`PaymentCompensationService.saveCompensatingEvent`)은 기본 전파(`REQUIRED`)라 같은 트랜잭션에 참여한다.
- 원장이 `CANCELLED`(사용자 취소)이고 보상 요청이 없으면 실패 처리 없이 보상 요청만 저장한다. 사용자 취소는 PG 결제를 취소하지 않으므로 PG에 결제가 남아 있다.
- 그 밖에 이미 처리된 결제면 `false`를 반환하고 보상 요청을 만들지 않는다. 승인 API와 웹훅이 동시에 금액 불일치를 처리해도 결제 락 때문에 보상 요청은 1건이다(`pg_id` UNIQUE가 최종 방어).
- PG 결제 실패(`failPayment`)는 돈이 움직이지 않았으므로 보상 요청을 만들지 않는다.

### 취소된 결제의 PG 결제 완료 (`requestCompensationForCancelled`)

- PG 결제창이 열려 있는 동안 사용자가 취소하고 이후 PG 결제가 완료되면, `approvePayment`가 락 안에서 `CANCELLED`를 확인해 `PAYMENT_ALREADY_CANCELLED`를 던진다.
- 예외로 `approvePayment` 트랜잭션은 롤백되므로, Facade가 `requestCompensationForCancelled`로 **별도 트랜잭션**에서 보상 요청을 저장한다.
  - 결제를 비관적 락으로 다시 조회해 `CANCELLED`이고 보상 요청이 없을 때만 저장한다. 동시에 들어와도 1건이다.
  - `CANCELLED`는 종료 상태라 두 트랜잭션 사이에 바뀌지 않는다.
  - 두 트랜잭션 사이에 서버가 종료되면 보상 요청이 없다. 웹훅은 `PROCESSING`으로 멈췄다가 다시 처리되고, 승인 API는 재호출 시 다시 판단해 보상 요청을 만든다.
- 사유는 "취소된 주문의 결제", 금액은 PG 조회 결과의 `totalAmount`다.

## 5. 처리 (`PaymentCompensationProcessor`)

### 즉시 시도와 스케줄러

| 경로 | 호출 | 시점 |
|---|---|---|
| 즉시 시도 | `PaymentFacade.reconcile` → `processByPgId(pgId)` | `failPaymentWithCompensation`·`requestCompensationForCancelled` 커밋 직후, 요청 스레드 |
| 재시도 | `PaymentCompensationScheduler` → `processPending(20)` | 30초 간격 |

- 둘 다 `process(id)`를 쓴다: 점유(행 잠금 후 `isProcessable` 재확인) → PG 전체 취소 → `recordDone` / `recordFailure`.
- **Processor는 예외를 밖으로 던지지 않는다.** 즉시 시도가 실패해도 보상 요청이 남아 재시도되므로, 승인 API는 `PAYMENT_INCORRECT_AMOUNT`(400)·`PAYMENT_ALREADY_CANCELLED`(409), 웹훅은 `COMPENSATED`로 끝난다.
- 즉시 시도와 스케줄러, 여러 인스턴스가 같은 요청을 고르면 두 번째는 락을 기다린 뒤 `PROCESSING`을 보고 건너뛴다. 락 대기 타임아웃(3초)이면 `PessimisticLockingFailureException`을 받아 건너뛴다.
- 결과 기록 중 `PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION`: 멈춘 행을 다시 점유한 사이 다른 처리가 먼저 기록한 경우라 실패로 기록하지 않고 건너뛴다.
- `processPending`은 요청마다 예외를 격리한다.

### PG 취소와 멱등성

- 보상은 **전체 취소**(`amount = null`)로 보낸다. PG에서 결제된 금액을 모두 돌려주는 것이 보상의 의미이고, 중복 호출 시 두 번째가 확실히 "이미 취소됨"으로 응답되기 때문이다.
- `PortOneClient.cancelPayment`의 멱등 키(`Idempotency-Key`)는 **호출마다 새로 만들고, 한 호출 안의 통신 재시도에서만 같은 키**를 쓴다.
  - PortOne은 같은 키의 응답(실패 포함)을 **3시간 동안 저장**해 돌려준다. 호출 간에 키를 고정하면 한 번 실패한 취소를 다시 시도해도 저장된 실패 응답만 받는다.
- 중복 환불 방지: 즉시 시도·스케줄러·멈춘 행 재점유가 겹쳐 같은 결제를 두 번 전체 취소해도, 두 번째는 `PAYMENT_ALREADY_CANCELLED`로 응답되고 성공(DONE)으로 처리된다.

### 스케줄러 설정

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 2          # 웹훅·보상 스케줄러가 서로의 PG 지연에 밀리지 않도록 스레드를 나눈다

payment:
  compensation:
    scheduler:
      enabled: true      # false면 스케줄러 빈을 등록하지 않음 (테스트에서는 false)
      fixed-delay: 30s   # 재시도 백오프가 1분부터라 웹훅(10s)보다 길게 둔다
      batch-size: 20     # 요청 하나가 PG 취소 호출 하나라 웹훅보다 작게 둔다
```

- 처리 중 멈춘 행을 다시 점유하는 기준(5분)은 PG 취소 최대 소요 시간(통신 실패 3회 × (연결 5초 + 읽기 10초) = 45초)보다 길어야 한다.

## 6. 장애 시나리오

| 실패 시점 | 결과 |
|---|---|
| `failPaymentWithCompensation` 트랜잭션 실패 | 결제 실패·보상 요청 모두 롤백. 다음 요청에서 다시 판단 |
| 커밋 직후 즉시 시도 전 서버 종료 | 보상 요청이 `REQUESTED`로 남아 스케줄러가 처리 |
| 즉시 PG 취소 실패 | `FAILED` + 백오프, 스케줄러 재시도 (새 멱등 키) |
| PG 취소 성공 후 `DONE` 기록 전 종료 | 5분 뒤 재점유 → 재취소는 "이미 취소됨"으로 성공 → `DONE` |
| 즉시 시도와 스케줄러 동시 점유 | 행 잠금 후 재확인으로 한쪽만 처리 |
| 승인 API·웹훅 동시 금액 불일치 | 결제 락으로 순차 실행, 두 번째는 `false`로 보상 요청 생략 |
| `PAYMENT_ALREADY_CANCELLED` 후 보상 요청 저장 전 종료 | 보상 요청 없음. 웹훅은 멈춘 행 재처리, 승인 API는 재호출 시 다시 판단해 저장 |
| 최대 시도 횟수 초과 | `DEAD` + error 로그 → 수동 환불 |

## 7. 테스트

| 테스트 | 종류 | 내용 |
|---|---|---|
| `PaymentCompensationTest` | 단위 | 생성 상태·금액 검증, 상태 전이, 백오프, DEAD, 실패 사유 자르기, 처리 가능 판단(요청 시각이 미래여도 REQUESTED는 대상) |
| `PaymentCompensationRepositoryTest` | `@DataJpaTest` + H2 | 36자 pgId·int 범위 초과 금액 저장, `pg_id` UNIQUE |
| `PaymentCompensationOutboxTest` | `@DataJpaTest` + H2, **실제 트랜잭션** | 결제 실패·주문 취소·보상 요청 함께 커밋, 보상 저장 실패 시 모두 롤백, 중복 요청 시 1건, 취소된 결제 보상 요청 커밋·중복 요청 시 1건 |
| `PaymentCompensationServiceTest` | `@DataJpaTest` + H2 | 점유(요청 시각이 미래인 REQUESTED 포함), 중복 점유 거부, 완료 후 점유 거부, 재시도 시각 전후, DEAD, 처리 대상 조회 |
| `PaymentCompensationProcessorTest` | Mockito | 전체 취소 후 DONE, 점유 실패·락 충돌 건너뜀, 취소 실패 기록, 다른 처리가 먼저 기록, 즉시 시도·예외 삼킴, 배치 예외 격리 |
| `PaymentCompensationSchedulerTest` | 단위 + `ApplicationContextRunner` | 배치 크기 전달, 예외 삼킴, 설정에 따른 빈 등록 |
| `PaymentCommandServiceTest` | Mockito | 금액 불일치 시 보상 저장(원장 CANCELLED면 실패 처리 없이 저장), 이미 처리된 결제면 저장 안 함, PG 실패는 보상 없음, 취소된 결제 보상 저장·이미 요청됨·취소 아님 |
| `PortOneClientTest` | `MockRestServiceServer` | 전체·부분 취소 모두 호출마다 다른 멱등 키, 한 호출 안의 재시도는 같은 키, 이미 취소됨 성공 처리 |

- `PaymentCompensationOutboxTest`는 테스트 메서드 트랜잭션에 묶이지 않도록 `NOT_SUPPORTED`로 실행하고 데이터를 직접 정리한다.

## 8. 남은 작업

### 배포 전 필수

- [ ] **`payment_compensations` 테이블 생성** (`ddl-auto: validate`). 시각 컬럼(`next_retry_at`, `processed_at`, `created_at`, `updated_at`)은 `DATETIME(6)`으로 만든다. 소수 초가 없으면 최대 0.5초 반올림되어 재시도 시각 비교가 어긋난다.

### 검증

- [ ] Testcontainers MySQL
  - 즉시 시도와 스케줄러가 같은 보상 요청을 동시에 점유해도 PG 취소는 한쪽만 한다
  - 승인 API와 웹훅이 동시에 금액 불일치를 처리해도 보상 요청은 1건이다

### 운영

- [ ] `DEAD` 보상 요청 확인 수단(알림, 조회 쿼리 또는 API)과 수동 환불 절차
- [ ] 즉시 시도가 요청 스레드에서 PG 취소를 기다린다(최악 약 45초). 필요하면 커밋 후 `@Async`로 전환한다. 보상 요청이 이미 저장돼 있어 안전성은 같다.
- [ ] 금액 불일치 응답 메시지에 "환불은 자동으로 진행됩니다" 포함 검토
