# 결제 웹훅 비동기 처리 (KAN-37)

PortOne 웹훅을 DB(Inbox)에 저장하고 스케줄러로 비동기 처리하는 구조와 남은 작업을 정리한 문서다.
결제 승인 구조와 설계 결정은 [payment-approval.md](payment-approval.md)를 참고한다.

## 1. 방향

- 웹훅은 **서명 검증 후 저장하고 즉시 200**을 응답한다. 결제 반영은 스케줄러가 한다.
- 저장 테이블(`webhook_events`)이 감사 기록, 중복 수신 제거, 재처리 상태를 모두 담당한다(Inbox 패턴).
- 웹훅 본문은 신뢰하지 않는다. `paymentId`만 꺼내고, 이벤트 종류와 무관하게 PG를 다시 조회해 판단한다(승인 API와 같은 로직).
- 비동기를 택한 이유
  - PortOne 재전송 정책(횟수 제한)에 의존하지 않고 우리 쪽에서 재시도를 관리할 수 있다.
  - 웹훅 응답 시간이 PG 조회 시간에 묶이지 않는다.
  - 보상 취소 Outbox(payment-approval.md 8장)와 스케줄러 구조를 공유할 수 있다.

```
[수신]  POST /api/payments/webhooks/portone        PortOneWebhookController (infra)
  PortOneWebhookVerifier.verify                    서명·타임스탬프 검증, 실패 시 401 (저장 안 함)
  WebhookEventCommandService.register              INSERT webhook_events, 이미 받은 webhook_id면 건너뜀
  → 항상 200

[처리]  WebhookEventScheduler (fixed-delay 10s)     → WebhookEventProcessor.processPending(50)
  findProcessableIds                               처리 대상 후보 조회 (잠금 없음)
  웹훅마다
    ① claim(id)            짧은 트랜잭션: 행 잠금 → 상태 재확인 → PROCESSING
    ② PaymentFacade.syncPayment(pgId)              트랜잭션 없음: PG 재조회 → 원장 반영
    ③ recordResult / recordFailure                 짧은 트랜잭션: PROCESSED / IGNORED / FAILED / DEAD
```

## 2. 구성

```text
infra/portone/webhook
├── PortOneWebhookController        POST /api/payments/webhooks/portone, 원문 본문(String)과 헤더 3개 수신
├── PortOneWebhookVerifier          SDK WebhookVerifier 래핑, PortOneWebhookEvent(eventType, pgId) 반환
└── dto/PortOneWebhookEvent

domain/payment
├── entity/WebhookEvent             Inbox 엔티티, 상태 전이·재시도 백오프
├── entity/enums/WebhookStatus      RECEIVED, PROCESSING, PROCESSED, IGNORED, FAILED, DEAD
├── repository/WebhookEventRepository   findByWebhookId, findWithLockById, findProcessableIds
├── service/WebhookEventCommandService  register, findProcessableIds, claim, recordResult, recordFailure
├── facade/WebhookEventProcessor        점유 → syncPayment → 결과 기록 (트랜잭션 없음)
├── facade/PaymentFacade.syncPayment    사용자 확인 없이 PG 상태를 원장에 반영, PaymentSyncResult 반환
├── dto/PaymentSyncResult               APPROVED, ALREADY_DONE, NOT_PAID, PG_FAILED, COMPENSATED, NOT_OUR_PAYMENT,
│                                       COMPENSATION_FAILED, STATE_CONFLICT
└── scheduler/WebhookEventScheduler     @Scheduled, payment.webhook.scheduler.enabled로 켜고 끔

global/config/SchedulingConfig          @EnableScheduling
```

- 컨트롤러는 infra에 둔다. PG 연동은 들어오는 쪽(웹훅)·나가는 쪽(`PortOneClient`) 모두 infra에 두는 기준이며, `PortOneConfigController`와 같다.
- infra는 서명 검증과 저장 요청까지만 하고, 처리(상태 관리·결제 반영)는 domain이 맡는다.

## 3. 수신 (`PortOneWebhookController`, `PortOneWebhookVerifier`)

### PortOne SDK `WebhookVerifier` (io.portone:server-sdk 0.24.0, 바이트코드로 확인)

- 시크릿: `whsec_` 접두사를 떼고 **Base64로 디코드**한다. 형식이 틀리면 빈 생성 시점(애플리케이션 시작)에 실패한다.
  - 운영: PortOne 콘솔의 `whsec_…` 값을 `PORTONE_WEBHOOK_SECRET`에 그대로 넣는다.
  - 테스트: `src/test/resources/config/application.yaml`에 Base64 값을 넣어 둔다.
- `verify(msgBody, msgId, msgSignature, msgTimestamp)`
  - 서명: `Base64(HMAC-SHA256(secret, "{id}.{timestamp}.{body}"))`, 헤더 값은 `v1,{서명}` (공백으로 여러 개 가능)
  - 타임스탬프 허용 범위: ±300초
  - 서명 검증 후 본문을 파싱한다. 알 수 없는 타입은 `Webhook.Unrecognized`로 반환된다(예외 아님).
  - 인자가 null이면 검증 예외가 아니라 NPE가 나므로 Verifier에서 먼저 막는다.
- 헤더 이름: `webhook-id`, `webhook-signature`, `webhook-timestamp` (`WebhookVerifier.HEADER_*` 상수)

### Verifier 반환값

| 웹훅 | `eventType` | `pgId` |
|---|---|---|
| 거래(`WebhookTransaction…`) | SDK 클래스 이름 (예: `WebhookTransactionPaid`) | `data.paymentId` |
| 빌링키 등 | 예: `WebhookBillingKeyIssued` | null |
| 알 수 없는 타입 | `Unrecognized` | null |

### 응답 규칙

| 상황 | 응답 | 저장 |
|---|---|---|
| 헤더 누락, 서명·타임스탬프 검증 실패 | 401 (`PAYMENT_WEBHOOK_INVALID_SIGNATURE`) | 저장 안 함, 경고 로그 |
| 처음 받은 거래 웹훅 | 200 | `RECEIVED` |
| 처음 받은 결제와 무관한 웹훅 | 200 | `IGNORED` |
| 이미 받은 `webhook_id` | 200 | 변화 없음 |

- 처리 결과와 무관하게 200을 응답한다. 처리 실패는 우리 쪽 재시도로 해결한다.
- `register`에는 트랜잭션을 걸지 않는다. 바깥 트랜잭션 안에서 UNIQUE 위반이 나면 rollback-only가 되어 재조회와 커밋이 실패하기 때문이다.

## 4. 저장 (`webhook_events`)

| 컬럼 | 설명 |
|---|---|
| `id` | PK |
| `webhook_id` | `webhook-id` 헤더. **UNIQUE**(100자), 중복 수신 제거 기준 |
| `pg_id` | 결제 연결용(40자, `payments.pg_id`와 동일). 결제와 무관한 웹훅은 null. 결제보다 먼저 올 수 있으므로 FK 없음 |
| `event_type` | Verifier의 `eventType`(50자) |
| `status` | `WebhookStatus` |
| `payload` | 원문 본문(`TEXT`). JSON 타입은 키 순서·공백을 정규화해 원문이 보존되지 않으므로 쓰지 않는다 |
| `attempt_count` | 처리 시도 횟수 |
| `next_retry_at` | 다음 재시도 시각 |
| `last_error` | 마지막 실패 사유(500자에서 자름) |
| `processed_at` | 처리 종료 시각 (`PROCESSED`, `IGNORED`, `DEAD`) |
| `created_at`, `updated_at` | `BaseTimeEntity`. 수신 시각, 처리 중 멈춘 행 판단 기준 |

- 인덱스: `pg_id`, `(status, next_retry_at)`
- `ddl-auto: validate`이므로 **테이블은 직접 만들어야 한다**(7장).

### 상태 전이 (`WebhookStatus.canTransitTo`)

```
생성 ── pgId 있음 → RECEIVED ──┐
     └ pgId 없음 → IGNORED     │
                               ▼
         FAILED ──────────→ PROCESSING ──→ PROCESSED
           ▲                  │  │  └───→ IGNORED   (원장에 없는 결제)
           └──────────────────┘  └──────→ DEAD      (최대 시도 횟수 초과, 수동 처리 필요 결과)
                    PROCESSING → PROCESSING (멈춘 처리 회수)
```

- 끝난 상태(`PROCESSED`, `IGNORED`, `DEAD`)에서는 어떤 전이도 허용하지 않는다(`PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION`, 409).
- 처리 가능 판단(`WebhookEvent.isProcessable`)
  - `RECEIVED`
  - `FAILED`이고 `next_retry_at <= now`
  - `PROCESSING`이고 `updated_at`이 5분 이상 지남 (처리 중 서버 종료)
- 재시도 백오프: 1분, 2분, 4분, 8분. 5번째 실패에서 `DEAD`(`WebhookEvent.MAX_ATTEMPTS`).

## 5. 처리 (`WebhookEventProcessor`, `PaymentFacade.syncPayment`)

### 점유와 동시성

- 후보 조회(`findProcessableIds`)는 잠그지 않는다. 점유(`claim`)에서 행을 `PESSIMISTIC_WRITE`로 잠그고 `isProcessable`을 다시 확인한다.
- 여러 인스턴스가 같은 웹훅을 고르면 두 번째는 락을 기다린 뒤 `PROCESSING`을 보고 건너뛴다.
- 락 대기 타임아웃(3초, payment-approval.md 4.5)에 걸리면 `PessimisticLockingFailureException`을 받아 건너뛴다.
- `SKIP LOCKED`는 쓰지 않는다. MySQL에서 Hibernate 힌트가 SQL에 반영되는지 확인되지 않았기 때문이다(락 타임아웃 힌트가 무시된 전례).

### `syncPayment` 결과

`PaymentFacade.confirmPayment`의 PG 조회 → 검증 → 반영 부분(`reconcile`)을 함께 쓰고, 업무상 예상되는 예외를 결과로 바꾼다.

| 상황 | `PaymentSyncResult` | 웹훅 상태 |
|---|---|---|
| 원장에 없는 `pgId` | `NOT_OUR_PAYMENT` | `IGNORED` |
| 이미 `PAID` / 이미 `FAILED`(`PAYMENT_ALREADY_PROCESSED`) | `ALREADY_DONE` | `PROCESSED` |
| PG 결제 완료, 금액 일치 → 승인 | `APPROVED` | `PROCESSED` |
| PG 미완료(`PAYMENT_NOT_PAID`) | `NOT_PAID` | `PROCESSED` |
| PG 실패(`PAYMENT_PG_FAILED`) → 결제 실패 처리 | `PG_FAILED` | `PROCESSED` |
| 금액 불일치(`PAYMENT_INCORRECT_AMOUNT`) → 실패 처리 + PG 보상 취소 | `COMPENSATED` | `PROCESSED` |
| 원장 `CANCELLED` + PG 결제 완료(`PAYMENT_ALREADY_CANCELLED`) → PG 보상 취소 | `COMPENSATED` | `PROCESSED` |
| 보상 중 PG 취소 실패(`PAYMENT_CANCELLATION_FAILED`) | `COMPENSATION_FAILED` | **즉시 `DEAD`** + error 로그 (수동 환불) |
| 원장 상태 때문에 승인 불가(`PAYMENT_INVALID_STATUS_TRANSITION`, `ORDER_INVALID_STATUS_TRANSITION`) | `STATE_CONFLICT` | **즉시 `DEAD`** + error 로그 (수동 확인) |
| 그 밖의 예외 (PG 조회 실패, PG에 결제 없음, 락 충돌, DB 오류) | 예외 전파 | `FAILED` → 재시도, 한도 초과 시 `DEAD` |

- `NOT_PAID`를 `PROCESSED`로 두는 이유: READY 등의 웹훅은 할 일이 없고, 이후 결제 완료 시 Paid 웹훅이 따로 온다.
- **재시도하지 않고 바로 `DEAD`로 보내는 경우**
  - `COMPENSATION_FAILED`: 결제 FAILED가 커밋된 뒤라, 재시도하면 `ALREADY_DONE` → `PROCESSED`로 끝나 **PG에 결제가 남은 상황이 가려진다.** 이슈 C(보상 Outbox) 전까지는 `DEAD`로 드러내고 수동 환불한다.
    - 원장 `CANCELLED`의 PG 취소 실패도 같은 결과로 받는다. 이 경우는 재시도하면 PG 취소를 다시 시도할 수 있지만, 지금은 금액 불일치와 똑같이 `DEAD`로 드러낸다.
  - `STATE_CONFLICT`: `approvePayment`가 락 안에서 PAID·FAILED·CANCELLED를 걸러낸 뒤의 전이 실패라 재시도해도 결과가 같다. 정상 흐름에서는 생기지 않는다.
    - 원장 `CANCELLED` + PG Paid는 여기에 해당하지 않는다. `COMPENSATED`로 PG 결제를 취소한다(payment-approval.md 4.3).
- **Processor의 예외 처리**
  - 결과 기록 중 `PAYMENT_WEBHOOK_INVALID_STATUS_TRANSITION`: 멈춘 행을 다시 점유한 사이 다른 처리가 먼저 기록한 경우다. 실패로 기록하지 않고 건너뛴다.
  - 그 밖의 예외는 `recordFailure`로 재시도를 예약한다.
  - `processPending`은 웹훅마다 예외를 격리해, 한 웹훅의 예외가 같은 배치의 나머지 처리를 막지 않는다.

### 스케줄러 설정

```yaml
payment:
  webhook:
    scheduler:
      enabled: true      # false면 스케줄러 빈을 등록하지 않음 (테스트에서는 false)
      fixed-delay: 10s   # 이전 실행이 끝난 뒤 다음 실행까지 대기, 실행이 겹치지 않음
      batch-size: 50     # 한 번에 처리할 최대 웹훅 수
```

## 6. 테스트

| 테스트 | 종류 | 내용 |
|---|---|---|
| `PortOneWebhookVerifierTest` | 단위 | 정상 서명(거래·빌링키·알 수 없는 타입), 잘못된 서명, 오래된 타임스탬프, 헤더 누락. 서명은 테스트에서 HMAC으로 직접 생성 |
| `PortOneWebhookControllerTest` | `@WebMvcTest` | 200(원문 저장), 중복 수신 200, 401이면 저장 안 함 |
| `WebhookEventTest` | 단위 | 생성 상태, 상태 전이, 백오프, DEAD(처리 종료 시각 기록), `markAsDead`, 실패 사유 자르기, `isProcessable` |
| `WebhookEventRepositoryTest` | `@DataJpaTest` + H2 | 36자 pgId·null pgId 저장, `webhook_id` UNIQUE |
| `WebhookEventCommandServiceTest` | `@DataJpaTest` + H2 | 저장·중복, 점유(대기·처리 중·IGNORED·재시도 시각 전후), 결과 기록(수동 처리 필요 결과는 DEAD), 처리 대상 조회 |
| `WebhookEventProcessorTest` | Mockito | 처리, 점유 실패·락 충돌 시 건너뜀, 실패 기록, 다른 처리가 먼저 기록하면 건너뜀, 수동 처리 필요 결과 기록, 배치 내 예외 격리 |
| `WebhookEventSchedulerTest` | 단위 + `ApplicationContextRunner` | 배치 크기 전달, 예외 삼킴, `enabled` 설정에 따른 빈 등록 |
| `PaymentFacadeTest` | Mockito | `syncPayment` 결과별(원장 CANCELLED 보상·보상 취소 실패·상태 충돌 포함), 재시도 필요 오류 전파 |

- DB는 `Instant`를 마이크로초로 반올림해 저장한다. 시각 경계를 비교하는 테스트는 `now`를 밀리초로 잘라 쓴다.

## 7. 남은 작업

### 배포 전 필수

- [ ] **`webhook_events` 테이블 생성**: `ddl-auto: validate`이므로 테이블이 없으면 애플리케이션이 시작되지 않는다. DDL 공유·적용 방식(마이그레이션 도구 도입 여부)을 팀과 정한다.
- [ ] **PortOne 콘솔에 웹훅 URL 등록**: `POST /api/payments/webhooks/portone`
- [ ] **인증 적용 시** `/api/payments/webhooks/**`를 인증 없이 허용(`permitAll`). 이 엔드포인트의 보안은 서명 검증이다.

### 검증

- [ ] Testcontainers MySQL
  - 두 Processor가 같은 웹훅을 동시에 점유해도 한쪽만 처리한다
  - 웹훅 처리와 승인 API가 동시에 들어와도 결제는 한 번만 `PAID`가 된다
- [ ] 실제 PortOne 웹훅 본문 형식 확인 (민감 정보 포함 여부, 저장 전 점검)

### 이슈 C. 보상 취소 Outbox

- 설계는 [payment-approval.md 8장](payment-approval.md#8-다음-단계-설계-보상-처리의-outbox-방식)을 따른다. 이번 스케줄러·Processor 구조를 재사용한다.
- 해결할 문제: 금액 불일치 보상에서 PG 취소가 실패하면 지금은 웹훅을 `DEAD`로 기록하고 수동 환불한다(5장). 승인 API 경로에서 실패한 경우는 웹훅 기록도 남지 않는다. **보상 취소를 자동으로 다시 시도하는 수단이 없다.**
- 함께 처리: 전체 취소 멱등 키를 결제마다 고정 (`PortOneClientTest`의 `@Disabled` 1건 활성화)

### 운영

- [ ] `DEAD` 웹훅 확인 수단(알림, 조회 쿼리 또는 API)
- [ ] 처리 완료 웹훅 보관 기간과 정리 배치
- [ ] **대사 배치**: 웹훅도 오지 않고 승인 API도 호출되지 않은 결제는 Inbox·Outbox로도 잡히지 않는다. PortOne 결제 목록과 원장을 비교하는 배치를 마지막 안전망으로 둔다.
- [ ] `eventType`은 SDK 클래스 이름이다. PortOne 원문 `type`(예: `Transaction.Paid`)으로 저장하려면 본문을 한 번 더 파싱해야 한다.
