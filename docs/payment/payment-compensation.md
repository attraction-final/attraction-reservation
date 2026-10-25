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
approvePayment (트랜잭션 1개): 취소된 결제 확인 + 보상 요청 INSERT → 커밋 (결제 상태는 그대로)
  → 즉시 PG 취소 1회 시도 (대부분 여기서 완료)
  → 실패하거나 시도 전 서버 종료 시 스케줄러가 이어받아 재시도
```

- 커밋된 보상 요청은 즉시 시도나 스케줄러 재시도로 **반드시 PG 취소가 시도된다.**
- 보상 요청 저장에 실패하면 결제 실패 처리도 함께 롤백된다.

| Outbox 패턴 | 이번 구조 |
|---|---|
| 업무 변경과 보낼 메시지를 같은 트랜잭션에 저장 | 결제 FAILED·주문 취소와 보상 요청을 같은 트랜잭션에 저장 (취소된 결제는 보상 요청만) |
| relay가 메시지를 외부로 전달 | Processor(즉시 시도)·스케줄러(재시도)가 PortOne 취소 API 호출 |
| at-least-once, 받는 쪽 멱등 | 같은 결제를 여러 번 취소해도 두 번째부터 "이미 취소됨"으로 성공 처리 |

- 웹훅 수신(`webhook_events`)은 **Inbox**(들어오는 메시지를 잃지 않음), 보상 취소(`payment_compensations`)는 **Outbox**(우리가 결정한 외부 호출을 잃지 않음)다. 두 패턴은 방향이 반대이고 함께 쓴다.

## 2. 구성

```text
domain/payment
├── entity/PaymentCompensation              보상 요청(Outbox) 엔티티, 상태 전이·재시도 백오프
├── entity/enums/CompensationStatus         REQUESTED, PROCESSING, PG_PENDING, FAILED, DONE, DEAD
├── repository/PaymentCompensationRepository    findByPgId, findWithLockById, findProcessableIds, findPgCheckableIds
├── service/PaymentCommandService           failPaymentWithCompensation: 결제 실패 + 보상 요청 저장 (같은 트랜잭션)
│                                           approvePayment: 취소된 결제면 보상 요청 저장 (같은 트랜잭션)
├── service/PaymentCompensationService      저장, 처리·확인 대상 조회, 행 잠금 후 점유, 완료·실패·PG 확인 대기 기록(점유 확인)
├── dto/ClaimedCompensation                 점유·확인 대상 요청에서 트랜잭션 밖 PG 호출·로그에 쓰는 값 (점유 시 시도 횟수, PG 취소 내역 ID 포함)
├── facade/PaymentCompensationProcessor     점유 → PG 전체 취소 → 결과 기록, PG 확인 대기 요청의 조회 기반 확정 (트랜잭션 없음)
├── facade/PaymentFacade                    금액 불일치·취소된 결제의 PG 결제 완료 시 보상 요청 후 즉시 1회 시도
└── scheduler/PaymentCompensationScheduler  payment.compensation.scheduler.enabled로 켜고 끔
```

- 보상은 결제 실패 처리와 같은 트랜잭션에서 생성되고 결제 포트(`PaymentGateway`)를 호출하므로 `domain/payment`에 둔다.
- 범용 `outbox_events` 대신 보상 전용 테이블을 쓴다. Outbox로 보낼 이벤트가 보상 취소 하나뿐이기 때문이다. 다른 외부 호출(부분 환불 등)이 생기면 범용화를 검토한다.
  - 환불(KAN-51)은 범용화하지 않고 환불 자체를 Outbox로 쓰는 전용 테이블(`refunds`)로 같은 구조(점유 → PG 호출 → 결과 기록, 백오프, 스케줄러)를 따랐다. 환불은 수량·상태 등 고유 데이터가 있어 환불 기록과 PG 취소 요청을 한 행으로 두는 편이 단순하다.

## 3. 저장 (`payment_compensations`)

| 컬럼 | 설명 |
|---|---|
| `id` | PK (IDENTITY) |
| `pg_id` | 결제 pgId(40자). **UNIQUE**, 결제당 보상 1건 |
| `amount` | PG가 실제 결제한 금액(bigint). 원장 금액과 다를 수 있다. 감사·로그·수동 환불용 |
| `reason` | PG 취소 사유(100자). 재시도 때도 같은 값을 보낸다 |
| `status` | `CompensationStatus` |
| `attempt_count` | 처리 시도 횟수 |
| `next_retry_at` | 다음 재시도 시각(NOT NULL). 생성 시 요청 시각. `REQUESTED`는 이 값과 무관하게 바로 처리 대상. `PG_PENDING`에서는 다음 PG 확인 시각 |
| `last_error` | 마지막 실패 사유(500자에서 자름) |
| `processed_at` | 처리 종료 시각(`DONE`, `DEAD`), 처리 전엔 null |
| `pg_cancellation_id` | PG 취소 내역 ID(100자). `PG_PENDING` 확정에 쓴다. 응답을 해석하지 못했으면 null |
| `pg_pending_since` | `PG_PENDING`이 된 시각. 확인 간격과 확인 기한(24시간)의 기준 |
| `created_at`, `updated_at` | `BaseTimeEntity`. 요청 시각, 처리 중 멈춘 행 판단 기준 |

- 인덱스: `(status, next_retry_at)`
- `ddl-auto: validate`이므로 **테이블은 직접 만들어야 한다**(8장).
- `pg_id`를 PK로 쓰지 않는다. 직접 정한 ID는 Spring Data `save()`가 merge(SELECT 후 INSERT)로 처리하고, 결제당 1건 규칙은 UNIQUE로 충분하다.

### 상태 전이 (`CompensationStatus.canTransitTo`)

```
생성 → REQUESTED ──→ PROCESSING ──→ DONE          (취소 응답 SUCCEEDED / 이미 취소됨)
                       ▲  │  ├───→ DEAD          (최대 시도 횟수 초과)
         FAILED ───────┘  │  └───→ PG_PENDING    (취소 응답 REQUESTED / 해석 불가)
           ▲              │
           └──────────────┘  (PG 취소 실패, 백오프 후 재시도)
         PROCESSING → PROCESSING (멈춘 처리 회수)

PG_PENDING ──→ DONE        (조회: 취소 완료)
           ├─→ FAILED      (조회: 취소 실패, 백오프 후 취소 재시도. 최대 시도 횟수면 DEAD)
           ├─→ DEAD        (확인 기한 24시간 초과)
           └─→ PG_PENDING  (아직 확정 안 됨·조회 실패, 다음 확인 예약)
```

- 끝난 상태(`DONE`, `DEAD`)에서는 어떤 전이도 허용하지 않는다(`PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION`, 409).
- 처리 가능 판단(`PaymentCompensation.isProcessable`)
  - `REQUESTED` (시각과 무관. DB가 시각을 반올림해 저장하면 생성 직후 값이 `now`보다 미래가 되어 즉시 시도가 건너뛰어질 수 있기 때문)
  - `FAILED`이고 `next_retry_at <= now`
  - `PROCESSING`이고 `updated_at`이 5분 이상 지남 (처리 중 서버 종료)
- 재시도 백오프: 1분, 2분, 4분, 8분. 5번째 실패에서 `DEAD` + error 로그("수동 환불 필요").
- `PG_PENDING`은 처리 가능 대상이 아니다(취소를 다시 보내지 않는다). 확인 대상(`isPgCheckable`)은 `PG_PENDING`이고 `next_retry_at <= now`인 요청이다.
- 생성 시 금액이 null이거나 0 이하면 `PAYMENT_INVALID_AMOUNT`.

## 4. 보상 요청 생성

### 금액 불일치 (`failPaymentWithCompensation`)

- 결제를 비관적 락으로 조회해 `PENDING`일 때만 결제 FAILED·주문 취소를 하고, 이어서 보상 요청을 저장한다.
  - 보상 저장(`PaymentCompensationService.saveCompensatingEvent`)은 기본 전파(`REQUIRED`)라 같은 트랜잭션에 참여한다.
- 원장이 `CANCELLED`(사용자 취소)이고 보상 요청이 없으면 실패 처리 없이 보상 요청만 저장한다. 사용자 취소는 PG 결제를 취소하지 않으므로 PG에 결제가 남아 있다.
- 그 밖에 이미 처리된 결제면 `false`를 반환하고 보상 요청을 만들지 않는다. 승인 API와 웹훅이 동시에 금액 불일치를 처리해도 결제 락 때문에 보상 요청은 1건이다(`pg_id` UNIQUE가 최종 방어).
- PG 결제 실패(`failPayment`)는 돈이 움직이지 않았으므로 보상 요청을 만들지 않는다.

### 취소된 결제의 PG 결제 완료 (`approvePayment`)

- PG 결제창이 열려 있는 동안 사용자가 취소하고 이후 PG 결제가 완료되면, `approvePayment`가 락 안에서 `CANCELLED`를 확인하고 **같은 트랜잭션에서 보상 요청을 저장**한 뒤 `CANCELLED` 상태를 반환한다. Facade는 즉시 1회 시도 후 `PAYMENT_ALREADY_CANCELLED`를 던진다.
  - `CANCELLED`이고 보상 요청이 없을 때만 저장한다. 결제 락 안에서 확인하므로 동시에 들어와도 1건이다.
  - 원장 확인과 보상 요청이 함께 커밋되므로 그 사이에 서버가 종료돼 보상 요청이 빠지는 경우가 없다.
  - 이전에는 예외로 롤백한 뒤 별도 트랜잭션(`requestCompensationForCancelled`)에서 저장해, 두 트랜잭션 사이에 승인 API 스레드가 종료되면 결제 완료 웹훅에만 복구를 의존했다.
- 사유는 "취소된 주문의 결제", 금액은 PG 조회 결과의 `totalAmount`다.

## 5. 처리 (`PaymentCompensationProcessor`)

### 즉시 시도와 스케줄러

| 경로 | 호출 | 시점 |
|---|---|---|
| 즉시 시도 | `PaymentFacade.reconcile` → `processByPgId(pgId)` | `failPaymentWithCompensation`·`approvePayment`(취소된 결제) 커밋 직후, 요청 스레드 |
| 재시도 | `PaymentCompensationScheduler` → `processPending(20, 5)` | 30초 간격 |

- 둘 다 `process(id)`를 쓴다: 점유(행 잠금 후 `isProcessable` 재확인) → PG 전체 취소 → 취소 응답 상태로 `recordDone` / `recordPgPending` / `recordFailure`.
- 스케줄러는 이어서 `findPgCheckableIds`로 고른 `PG_PENDING` 요청을 `confirmPending(id)`로 확인한다(아래 "PG 취소 결과 확인").
- **Processor는 예외를 밖으로 던지지 않는다.** 즉시 시도가 실패해도 보상 요청이 남아 재시도되므로, 승인 API는 `PAYMENT_INCORRECT_AMOUNT`(400)·`PAYMENT_ALREADY_CANCELLED`(409), 웹훅은 `COMPENSATED`로 끝난다.
- 즉시 시도와 스케줄러, 여러 인스턴스가 같은 요청을 고르면 두 번째는 락을 기다린 뒤 `PROCESSING`을 보고 건너뛴다. 락 대기 타임아웃(3초)이면 `PessimisticLockingFailureException`을 받아 건너뛴다.
- 결과 기록(`recordDone`·`recordFailure`·`recordPgPending`)은 점유할 때의 시도 횟수를 함께 받아, 행이 아직 `PROCESSING`이고 시도 횟수가 같을 때만 기록한다(펜싱). 멈춘 행으로 보고 다른 처리가 다시 점유했다면 시도 횟수가 달라 이전 처리의 늦은 결과가 거부된다.
- 결과 기록 중 `PAYMENT_COMPENSATION_INVALID_STATUS_TRANSITION`: 다른 처리가 먼저 기록했거나 다시 점유한 경우라 실패로 기록하지 않고 건너뛴다.
- `processPending`은 요청마다 예외를 격리한다.

### 재시도 전 PG 확인

이전 시도가 취소를 보낸 뒤 결과를 기록하지 못했을 수 있다(서버 종료, `recordPgPending` 등 결과 기록 실패, 통신 실패 3회, 409 `IDEMPOTENCY_OUTSTANDING_REQUEST`). 이때 취소를 바로 다시 보내면, 이전 취소가 PG에서 진행 중(`REQUESTED`)일 때 PortOne이 어떻게 응답할지 확인되지 않은 요청을 보내게 된다. 그래서 **재시도(점유 시 `attempt_count` 2 이상)는 취소 전에 PG 결제를 먼저 조회한다.** 첫 시도는 보낸 취소가 없으므로 조회하지 않는다.

| 조회 결과 | 처리 | 취소 요청 |
|---|---|---|
| PG 결제가 `CANCELLED` | `DONE` (이전 취소가 이미 반영됨) | 보내지 않음 |
| `cancellations`에 `REQUESTED` 내역이 있음 | 그 내역 ID로 `PG_PENDING` | 보내지 않음 |
| 그 밖 (`PAID`이면서 진행 중인 취소 없음, 이전 취소 `FAILED`, PG에 결제 없음) | 기존 흐름 | 보냄 |
| 조회 실패 | `FAILED` (백오프 후 다시 확인) | 보내지 않음 |

- 조회 실패 시 취소를 보내지 않는다. PortOne 조회가 실패할 때는 취소 API도 실패할 가능성이 높고, 결과를 모른 채 다시 보내지 않는 편이 안전하다. 장애가 길어 최대 시도 횟수를 넘기면 `DEAD`로 수동 확인한다.

### PG 취소와 멱등성

- 보상은 **전체 취소**(`amount = null`)로 보낸다. PG에서 결제된 금액을 모두 돌려주는 것이 보상의 의미이고, 중복 호출 시 두 번째가 확실히 "이미 취소됨"으로 응답되기 때문이다.
- `PortOneClient.cancelPayment`의 멱등 키(`Idempotency-Key`)는 **호출마다 새로 만들고, 한 호출 안의 통신 재시도에서만 같은 키**를 쓴다.
  - PortOne은 같은 키의 응답(실패 포함)을 **3시간 동안 저장**해 돌려준다. 호출 간에 키를 고정하면 한 번 실패한 취소를 다시 시도해도 저장된 실패 응답만 받는다.
- 중복 환불 방지: 즉시 시도·스케줄러·멈춘 행 재점유가 겹쳐 같은 결제를 두 번 전체 취소해도, 두 번째는 `PAYMENT_ALREADY_CANCELLED`로 응답되고 성공(DONE)으로 처리된다.

### PG 취소 결과 확인 (`PG_PENDING`)

PortOne은 HTTP 200이어도 취소 응답의 `cancellation.status`가 `REQUESTED`(취소 요청)·`FAILED`(취소 실패)·`SUCCEEDED`(취소 완료) 중 하나다. 200만 보고 `DONE`으로 기록하면 실제로 취소되지 않은 결제가 완료로 남는다. ([PortOne REST API V2 – 결제](https://developers.portone.io/api/rest-v2/payment?v=v2)의 `CancelPaymentResponse`, `PaymentCancellation`)

| 취소 응답 | `PortOneClient.cancelPayment` | 보상 |
|---|---|---|
| 200 + `SUCCEEDED`, 409 `PAYMENT_ALREADY_CANCELLED` | `SUCCEEDED` 반환 | `DONE` |
| 200 + `REQUESTED` | `REQUESTED` + 취소 내역 ID 반환 | `PG_PENDING` (취소 내역 ID 저장) |
| 200인데 취소 내역이 없거나 본문을 읽지 못함(형식 오류, 처리할 수 없는 content-type) | `UNKNOWN` 반환, 취소 내역 ID 없음 | `PG_PENDING` (ID 없음) |
| 200인데 취소 상태 값을 모름 | `UNKNOWN` + 취소 내역 ID 반환 | `PG_PENDING` (ID 기준으로 확인) |
| 200 + `FAILED`, 4xx·5xx, 통신 실패 | `PAYMENT_CANCELLATION_FAILED` 예외 | `FAILED` (백오프 후 재시도) |

- `PG_PENDING`에서는 **취소를 다시 보내지 않는다.** 취소가 이미 반영됐을 수 있으므로 PG 결제 조회(`GET /payments/{id}`)로만 확정한다.
- PortOne 결제 조회 응답은 결제 완료(`PAID`) 상태에서도 `cancellations`(결제 취소 요청 내역)를 돌려준다(같은 문서의 `PaidPayment.cancellations`). 확정 판단(`confirmPending`):
  - 취소 내역 ID가 있으면 같은 ID의 내역 상태로 판단: `SUCCEEDED` → `DONE`, `FAILED` → `FAILED`(취소 재시도), 그 밖·목록에 없음 → 다음 확인 예약
  - 취소 내역 ID가 없으면(취소 내역이 없거나 본문을 읽지 못함) PG 결제가 `CANCELLED`일 때 `DONE`. 보상은 항상 전체 취소라 이 기준이 맞다
  - PG 조회 실패·PG에 결제 없음 → 실패로 기록하지 않고 다음 확인 예약
- 확인 간격은 `PG_PENDING`이 된 뒤 지난 시간(1분~30분)이라 대략 두 배씩 늘어난다. 24시간 안에 확정되지 않으면 `DEAD` + error 로그("PG 취소 확인 기한 초과, 수동 확인 필요").
- 확인은 읽기만 하므로 점유하지 않는다. 확인 결과 기록(`recordPgCancelled`·`recordPgCancelFailed`·`reschedulePgCheck`)은 확인 대상으로 꺼낼 때의 `pg_pending_since`를 함께 받아, 행을 잠근 뒤 같은 `PG_PENDING` 주기일 때만 기록한다. 그래서 확정(`DONE`·`FAILED`·`DEAD`)은 한 번만 반영되고, 확인 사이에 취소 실패 → 재취소로 새 주기가 됐으면 이전 주기의 늦은 결과는 거부된다. 다음 확인 예약은 겹쳐도 같은 주기 안에서 시각만 다시 정한다.
- 확정은 스케줄러의 조회가 책임진다. 취소 웹훅이 오지 않거나 처리에 실패해도 확정된다.

### 스케줄러 설정

```yaml
spring:
  task:
    scheduling:
      pool:
        size: 3          # 웹훅·보상·환불(KAN-51) 스케줄러가 서로의 PG 지연에 밀리지 않도록 스레드를 나눈다

payment:
  compensation:
    scheduler:
      enabled: true      # false면 스케줄러 빈을 등록하지 않음 (테스트에서는 false)
      fixed-delay: 30s   # 재시도 백오프가 1분부터라 웹훅(10s)보다 길게 둔다
      batch-size: 20     # 요청 하나가 PG 호출 하나라 웹훅보다 작게 둔다. 취소 대상에 적용된다
      pg-check-batch-size: 5   # 한 번에 확인할 최대 PG_PENDING 수 (확인마다 PG 조회)
```

- `processPending`은 취소 대상을 `batch-size`, PG 확인 대상을 `pg-check-batch-size`로 각각 조회하므로, 한 번 실행에 PG 호출은 최대 `batch-size` + `pg-check-batch-size`(기본 25회)다. 재시도는 취소 전 조회가 더해져 요청 하나가 PG 호출 2회일 수 있다.
- 확인 건수를 따로 작게 두는 이유: PG 장애가 복구될 때까지 `PG_PENDING`이 한꺼번에 쌓일 수 있는데, 확인마다 PG를 조회하므로(최대 약 15초) 같은 스레드의 취소 처리가 밀린다. 기본값 5건이면 한 주기의 확인 지연은 최대 약 75초이고, 복구 후에는 30초마다 5건(시간당 약 600건)씩 확정한다. 확인 기한(24시간) 안에 약 1만 4천 건까지 확정할 수 있다.
- 처리 중 멈춘 행을 다시 점유하는 기준(5분)은 PG 취소 최대 소요 시간(통신 실패 3회 × (연결 5초 + 읽기 10초) = 45초)보다 길어야 한다.

## 6. 장애 시나리오

| 실패 시점 | 결과 |
|---|---|
| `failPaymentWithCompensation` 트랜잭션 실패 | 결제 실패·보상 요청 모두 롤백. 다음 요청에서 다시 판단 |
| 커밋 직후 즉시 시도 전 서버 종료 | 보상 요청이 `REQUESTED`로 남아 스케줄러가 처리 |
| 즉시 PG 취소 실패 | `FAILED` + 백오프, 스케줄러 재시도 (새 멱등 키) |
| PG 취소 성공 후 `DONE` 기록 전 종료 | 5분 뒤 재점유 → 재시도 전 조회에서 PG `CANCELLED` 확인 → 취소를 다시 보내지 않고 `DONE` |
| `REQUESTED` 응답 후 `PG_PENDING` 기록 전 종료·기록 실패 | 재점유 또는 `FAILED` 재시도 → 재시도 전 조회에서 `REQUESTED` 내역 확인 → 취소를 다시 보내지 않고 `PG_PENDING` |
| 재시도 전 PG 조회 실패 | 취소를 보내지 않고 `FAILED` + 백오프 |
| 즉시 시도와 스케줄러 동시 점유 | 행 잠금 후 재확인으로 한쪽만 처리 |
| 승인 API·웹훅 동시 금액 불일치 | 결제 락으로 순차 실행, 두 번째는 `false`로 보상 요청 생략 |
| 취소된 결제 승인 중 종료 | 원장 확인과 보상 요청이 같은 트랜잭션이라 커밋 전 종료면 둘 다 롤백(다음 요청에서 다시 판단), 커밋 후 종료면 보상 요청이 `REQUESTED`로 남아 스케줄러가 처리 |
| 최대 시도 횟수 초과 | `DEAD` + error 로그 → 수동 환불 |
| PG가 취소를 접수만 함(`REQUESTED`) | `PG_PENDING`, 취소 재요청 없이 조회로 확정 |
| 취소 성공 응답에 취소 내역이 없거나 본문을 읽지 못함 | `PG_PENDING`(ID 없음), PG 결제 `CANCELLED`면 `DONE` |
| `PG_PENDING` 확인 중 PG 조회 실패 | 실패로 기록하지 않고 다음 확인 예약 |
| `PG_PENDING` 24시간 내 미확정 | `DEAD` + error 로그 → 수동 확인 |
| 멈춘 행 재점유 후 이전 처리의 늦은 결과 | 시도 횟수가 달라 기록 거부(펜싱), 새 점유의 결과만 반영 |
| 이전 `PG_PENDING` 주기의 늦은 확인 결과 | `pg_pending_since`가 달라 기록 거부(펜싱) |

## 7. 테스트

| 테스트 | 종류 | 내용 |
|---|---|---|
| `PaymentCompensationTest` | 단위 | 생성 상태·금액 검증, 상태 전이, 백오프, DEAD(처리 종료 시각 기록), 실패 사유 자르기, 처리 가능 판단(요청 시각이 미래여도 REQUESTED는 대상), `PG_PENDING` 전이·확인 간격·확인 기한, 점유 확인(펜싱), PG 확인 대기 주기 확인(펜싱) |
| `PaymentCompensationRepositoryTest` | `@DataJpaTest` + H2 | 36자 pgId·int 범위 초과 금액 저장, `pg_id` UNIQUE |
| `PaymentCompensationOutboxTest` | `@DataJpaTest` + H2, **실제 트랜잭션** | 결제 실패·주문 취소·보상 요청 함께 커밋, 보상 저장 실패 시 모두 롤백, 중복 요청 시 1건, 취소된 결제 승인 시 보상 요청이 같은 트랜잭션으로 커밋·다시 승인해도 1건 |
| `PaymentCompensationServiceTest` | `@DataJpaTest` + H2 | 점유(요청 시각이 미래인 REQUESTED 포함), 중복 점유 거부, 완료 후 점유 거부, 재시도 시각 전후, DEAD, 처리 대상 조회, `PG_PENDING` 기록·확인 대상 조회·확정, 재점유 후 늦은 실패 기록 거부, 이전 PG 확인 대기 주기의 늦은 확인 결과 거부 |
| `PaymentCompensationClaimConcurrencyTest` | `@SpringBootTest` + Testcontainers MySQL | 두 처리가 동시에 점유해도 한쪽만 점유, 락을 잡은 점유 트랜잭션 커밋 후 대기하던 점유는 건너뜀 |
| `PaymentCompensationProcessorTest` | Mockito | 전체 취소 후 DONE, 점유 실패·락 충돌 건너뜀, 취소 실패 기록, 다른 처리가 먼저 기록, 즉시 시도·예외 삼킴, 배치 예외 격리, 취소 응답 REQUESTED·UNKNOWN이면 `PG_PENDING`, 조회 결과별 확정(완료·실패·미확정·내역 없음·조회 실패·결제 없음·ID 없음), `PG_PENDING`에서 취소 미호출, 재시도 전 조회(이미 취소·접수된 취소·미반영·결제 없음·조회 실패, 첫 시도는 조회 안 함) |
| `PaymentCompensationSchedulerTest` | 단위 + `ApplicationContextRunner` | 배치 크기 전달, 예외 삼킴, 설정에 따른 빈 등록 |
| `PaymentFacadeTest` | Mockito | 금액 불일치 시 보상 요청 후 `processByPgId` 즉시 시도·이미 처리됐으면 생략, 취소된 결제의 PG 결제 완료 시 `approvePayment`의 CANCELLED 응답으로 즉시 시도 후 `PAYMENT_ALREADY_CANCELLED`(중복은 Processor가 건너뜀)·PG 금액을 보상 금액으로 전달, 웹훅 `COMPENSATED` |
| `PaymentCommandServiceTest` | Mockito | 금액 불일치 시 보상 저장(원장 CANCELLED면 실패 처리 없이 저장), 이미 처리된 결제면 저장 안 함, PG 실패는 보상 없음, `approvePayment`의 취소된 결제 보상 저장·이미 요청됨·취소 아님·보상 저장 실패 시 예외 전파 |
| `PortOneClientTest` | `MockRestServiceServer` | 전체·부분 취소 모두 호출마다 다른 멱등 키, 한 호출 안의 재시도는 같은 키, 전체 취소의 이미 취소됨 성공 처리 (부분 취소의 이미 취소됨은 예외, KAN-51), 취소 응답 상태별 반환(SUCCEEDED·REQUESTED·FAILED 예외·UNKNOWN·내역 없음·해석 불가·처리할 수 없는 content-type), 결제 조회의 취소 내역 매핑 |

- `PaymentCompensationOutboxTest`는 테스트 메서드 트랜잭션에 묶이지 않도록 `NOT_SUPPORTED`로 실행하고 데이터를 직접 정리한다.

## 8. 남은 작업

### 배포 전 필수

- [ ] **`payment_compensations` 테이블 생성** (`ddl-auto: validate`). 시각 컬럼(`next_retry_at`, `processed_at`, `pg_pending_since`, `created_at`, `updated_at`)은 `DATETIME(6)`으로 만든다. 소수 초가 없으면 최대 0.5초 반올림되어 재시도 시각 비교가 어긋난다.
- [ ] **이미 테이블을 만들었다면 PG 확인 대기 컬럼 추가** (KAN-66). 애플리케이션 배포 전에 적용한다.
  ```sql
  ALTER TABLE payment_compensations
      ADD COLUMN pg_cancellation_id VARCHAR(100) NULL,
      ADD COLUMN pg_pending_since DATETIME(6) NULL;
  ```
  - **`status` 컬럼 타입을 먼저 확인한다**(`SHOW CREATE TABLE payment_compensations`). Hibernate 7은 MySQL에서 `@Enumerated(STRING)` 컬럼을 `ENUM`으로 만든다(MySQL 9.6 컨테이너에서 확인: `enum('DEAD','DONE','FAILED','PROCESSING','REQUESTED')` → KAN-66 이후 `PG_PENDING` 포함). Hibernate DDL로 만든 테이블이면 `PG_PENDING`을 넣기 전에 값을 추가해야 한다. 추가하지 않으면 `PG_PENDING` 기록이 실패해 `FAILED` 재시도로 빠진다.
    ```sql
    ALTER TABLE payment_compensations
        MODIFY COLUMN status ENUM('DEAD','DONE','FAILED','PG_PENDING','PROCESSING','REQUESTED') NOT NULL;
    ```
  - `VARCHAR(20)`으로 만들었다면 `PG_PENDING`(10자)이 그대로 들어가므로 변경하지 않는다.

### 검증

- [ ] Testcontainers MySQL
  - [x] 같은 보상 요청을 동시에 점유해도 한쪽만 점유한다 (`PaymentCompensationClaimConcurrencyTest`)
  - [ ] 승인 API와 웹훅이 동시에 금액 불일치를 처리해도 보상 요청은 1건이다
  - [ ] 승인 API와 웹훅이 동시에 취소된 결제의 PG 결제 완료를 처리해도 보상 요청은 1건이다

### 운영

- [ ] `DEAD` 보상 요청 확인 수단(알림, 조회 쿼리 또는 API)과 수동 환불 절차. 확인 기한 초과(`last_error` = "PG 취소 확인 기한 초과")는 PG 취소 내역을 먼저 확인한다
- [ ] 결제 수단 확정 후 PortOne 테스트 채널에서 실제 취소로 확인: 취소 응답 상태, `REQUESTED`일 때 결제 조회의 `cancellations`·`amount.cancelled`, 웹훅 수신 없이 스케줄러만으로 확정
- [ ] PortOne 호출 제한을 확인하고, 필요하면 PG 확인 대상의 배치 크기를 취소 대상과 분리한다
- [ ] (선택) 취소 웹훅 수신 시 해당 pgId의 `PG_PENDING` 요청 `next_retry_at`을 즉시로 당겨 확정 앞당기기
- [ ] 즉시 시도가 요청 스레드에서 PG 취소를 기다린다(최악 약 45초). 필요하면 커밋 후 `@Async`로 전환한다. 보상 요청이 이미 저장돼 있어 안전성은 같다.
- [ ] 금액 불일치 응답 메시지에 "환불은 자동으로 진행됩니다" 포함 검토
