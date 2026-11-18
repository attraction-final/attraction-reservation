# 결제 웹훅 E2E 재현 — 로컬 Docker에서 실패 상황까지 확인

## 목적과 결론

결제 결과가 승인 API·웹훅·만료 스케줄러 중 어느 경로로 들어오든 원장이 PG의 실제 상태와 맞는지, 실제 앱을 띄워 확인했다. PortOne 대신 WireMock이 응답해 PG 장애와 복구를 원하는 시점에 만들었다.

다섯 시나리오가 모두 기대대로 동작했다. 서명이 틀린 웹훅은 저장하지 않았고, 같은 웹훅은 한 번만 저장했다. PG 장애 중 받은 웹훅은 실패로 기록된 뒤 1분 뒤 재시도에서 처리됐고, 승인 API·웹훅이 모두 없던 결제 완료 주문은 만료 스케줄러가 취소하지 않고 승인했다.

## 환경

| 항목 | 조건 |
| --- | --- |
| 일시 | 2026-11-18 |
| 앱 | 레포 `Dockerfile`로 빌드한 이미지, 기본 프로필 (웹훅·보상·만료 스케줄러 켜짐) |
| DB | MySQL 8.4 (운영 RDS와 같은 버전), Flyway가 스키마 생성 |
| 캐시 | Redis 7.4 |
| PG | WireMock 3.9 (`wiremock/mappings`), 장애·복구는 관리 API로 응답을 바꿈 |
| 웹훅 | `send_webhook.py`가 Standard Webhooks 방식으로 서명 (`PORTONE_WEBHOOK_SECRET`과 같은 테스트 키) |
| 데이터 | `seed.sql`: 회원 1명, 결제 대기 주문 3건 (종일권 50,000원) |

비밀값은 이 환경 전용 테스트 값이다.

## 실행

```bash
./gradlew bootJar -x test
cd perf/webhook-e2e
docker compose up -d --build                     # 앱 health 200까지 1~2분
docker compose exec -T mysql mysql -uroot -pe2e attraction_reservation < seed.sql

python send_webhook.py pay-e2e-001 wh-e2e-001                   # 1 정상
python send_webhook.py pay-e2e-001 wh-e2e-001                   # 2 같은 웹훅 재전송
python send_webhook.py pay-e2e-001 wh-e2e-003 --bad-signature   # 3 잘못된 서명
python send_webhook.py pay-e2e-004 wh-e2e-004                   # 4 PG 장애(500) 중 수신

# 4: 첫 실패 뒤 PG 복구 (재시도 예정 시각 전에)
curl -X PUT http://localhost:18089/__admin/mappings/6f0b1c2a-0000-4000-8000-000000000004 \
  -H 'Content-Type: application/json' \
  -d '{"request":{"method":"GET","urlPathPattern":"/payments/pay-e2e-004"},"response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"id":"pay-e2e-004","status":"PAID","amount":{"total":50000,"cancelled":0},"paidAt":"{{now}}"},"transformers":["response-template"]}}'

# 5는 seed.sql의 40분 지난 주문 9005를 만료 스케줄러(1분 주기)가 처리한다.
docker compose down -v
```

## 결과

| # | 시나리오 | 응답 | 결과 |
| --- | --- | --- | --- |
| 1 | 정상 웹훅 (PG: 결제 완료) | 200 | 2초 뒤 스케줄러가 PG 재조회 → 결제 PAID, 주문 COMPLETED, 티켓 1장, 웹훅 PROCESSED |
| 2 | 같은 `webhook-id` 재전송 | 200 | `WEBHOOK_DUPLICATED`, `webhook_events` 행 추가 없음 |
| 3 | 잘못된 서명 | 401 | `WEBHOOK_VERIFICATION_FAILED`, 저장 안 함 |
| 4 | PG 장애(500) 중 수신 → 약 35초 뒤 PG 복구 | 200 | FAILED, `next_retry_at` 1분 뒤 → 재시도에서 PROCESSED (`attempt_count` 2), 결제 PAID |
| 5 | 승인 API·웹훅 없이 40분 지난 주문 (PG: 결제 완료) | - | 만료 스케줄러가 취소하지 않고 승인 (`ORDER_AUTO_CANCEL_PROCESSED count=0 skippedCount=1`), 결제 PAID, 티켓 1장 |

### 최종 DB 상태

| webhook_id | pg_id | status | attempt_count | processed_at (UTC) |
| --- | --- | --- | --- | --- |
| wh-e2e-001 | pay-e2e-001 | PROCESSED | 1 | 11:47:00 |
| wh-e2e-004 | pay-e2e-004 | PROCESSED | 2 | 11:48:09 |

| 주문 | pg_id | 주문 | 결제 | 티켓 |
| --- | --- | --- | --- | --- |
| 9001 | pay-e2e-001 | COMPLETED | PAID | 1 |
| 9004 | pay-e2e-004 | COMPLETED | PAID | 1 |
| 9005 | pay-e2e-005 | COMPLETED | PAID | 1 |

### 앱 로그 (공통 필드 일부 생략)

```text
11:46:58.659 INFO event=WEBHOOK_RECEIVED webhookId=wh-e2e-001 eventType=WebhookTransactionPaid pgId=pay-e2e-001
11:46:58.880 INFO event=WEBHOOK_DUPLICATED webhookId=wh-e2e-001
11:46:59.063 INFO event=WEBHOOK_VERIFICATION_FAILED webhookId="wh-e2e-003" reason="No matching signature found" errorCode=PAYMENT_WEBHOOK_INVALID_SIGNATURE
11:46:59.264 INFO event=WEBHOOK_RECEIVED webhookId=wh-e2e-004 eventType=WebhookTransactionPaid pgId=pay-e2e-004
11:47:00.659 INFO event=PAYMENT_APPROVED pgId=pay-e2e-001 orderId=9001 targetUserId=9001 amount=50000
11:47:00.660 INFO event=TICKETS_ISSUED orderId=9001 targetUserId=9001 count=1
11:47:00.677 INFO event=WEBHOOK_PROCESSED eventId=1 pgId=pay-e2e-001 result=APPROVED
11:47:00.719 WARN event=WEBHOOK_PROCESS_FAILED eventId=2 pgId=pay-e2e-004 errorCode=PAYMENT_GATEWAY_ERROR
11:47:39.541 INFO event=PAYMENT_APPROVED pgId=pay-e2e-005 orderId=9005 targetUserId=9001 amount=50000
11:47:39.541 INFO event=TICKETS_ISSUED orderId=9005 targetUserId=9001 count=1
11:47:39.542 INFO event=ORDER_AUTO_CANCEL_PROCESSED count=0 failedCount=0 skippedCount=1 durationMs=37 result=SUCCESS failureCodes=""
11:48:09.036 INFO event=PAYMENT_APPROVED pgId=pay-e2e-004 orderId=9004 targetUserId=9001 amount=50000
11:48:09.036 INFO event=TICKETS_ISSUED orderId=9004 targetUserId=9001 count=1
11:48:09.055 INFO event=WEBHOOK_PROCESSED eventId=2 pgId=pay-e2e-004 result=APPROVED
```

## 한계

- PortOne 실제 서버가 아니라 WireMock 응답이다. 응답 형식은 `PortOnePaymentResponse`가 읽는 필드(`id`, `status`, `amount`, `paidAt`)만 맞췄다.
- 웹훅 처리가 5회 연속 실패해 DEAD가 되는 경로, 처리 중 앱이 죽어 PROCESSING에 멈춘 웹훅을 5분 뒤 다시 점유하는 경로는 이번에 재현하지 않았다. 단위·통합 테스트(`WebhookEventTest`, `WebhookEventRepositoryTest`)가 다룬다.
- 처리 완료 뒤에도 `last_error`에 이전 실패 사유가 남는다. 동작에는 영향이 없다.
