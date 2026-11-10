# 애플리케이션 로깅 컨벤션

Oct 30, 2026 · @xevbn

## 1. 목적과 적용 범위

모든 로그는 한 줄 전체를 logfmt(`key=value`) 형식으로 출력하고, 메시지는 `event=` 로 시작한다. 이 규칙만 지키면 Loki에서 `| logfmt` 한 번으로 모든 값을 필드처럼 검색·집계할 수 있다.

- 대상 환경: Spring Boot 4.x, SLF4J(Logback), Grafana Loki
- 적용 대상: 애플리케이션 코드에서 남기는 모든 로그 (`log.info`, `log.warn`, `log.error` 등)
- 이 문서에 없는 경우는 "나중에 이 로그를 무엇으로 검색할 것인가"를 기준으로 판단하고, 결정한 내용은 이 문서에 추가한다.

## 2. 로그 한 줄의 구조

로그 한 줄은 "공통 필드(자동)" + "메시지(개발자 작성)" 두 부분으로 구성되며, 공통 필드는 Logback 패턴이 붙이므로 메시지에 다시 쓰지 않는다.

```
ts=2026-10-30T14:23:05.123+09:00 level=INFO logger=c.e.order.OrderService thread=http-nio-8080-exec-3 traceId=4bf92f3577b34da6 event=ORDER_CREATED orderId=A1024 userId=u-881
```

| 구분 | 키 | 출처 |
| --- | --- | --- |
| 공통 | `ts`, `level`, `logger`, `thread`, `traceId` | Logback 패턴 + MDC (자동) |
| 메시지 | `event` + 상황별 키 | 개발자가 코드에서 작성 |

출력 패턴은 모든 프로필에서 아래 설정을 공통으로 사용한다.

```yaml
# application.yml
logging:
  pattern:
    console: "ts=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} level=%level logger=%logger{36} thread=%thread traceId=%X{traceId:-} %msg%n"
```

- 타임스탬프는 ISO 8601에 오프셋(`+09:00`)을 포함한다.
- `traceId`가 없을 때는 빈 값으로 출력한다 (`%X{traceId:-}`).

## 3. 메시지 작성 규칙

메시지는 `event=이벤트이름` 다음에 `key=value` 쌍만 나열하며, 문장형 설명은 쓰지 않는다.

```
log.info("event=ORDER_CREATED orderId={} userId={} amount={}", orderId, userId, amount);
```

1. **`event=`로 시작한다.** 모든 로그에 예외 없이 적용한다.
2. **이벤트 이름은 `대상_동작` 형태의 대문자 스네이크 케이스**로 쓴다. 동작은 과거형으로 쓴다.
   - 좋음: `ORDER_CREATED`, `PAYMENT_FAILED`, `AUTHENTICATION_REJECTED`
   - 나쁨: `CreateOrder`, `order created`, `ERROR_OCCURRED`
3. **키는 camelCase**로 쓰고, 4장의 표준 키 사전에 있는 이름을 우선 사용한다.
4. **가변 값은 반드시 `{}` 플레이스홀더**로 넘긴다. `+`로 변수를 이어 붙이지 않는다. (리터럴끼리의 `+`는 허용)
5. **공백이 들어갈 수 있는 값은 따옴표로 감싼다.** ID, enum 이름, 숫자처럼 공백이 없는 값은 그대로 쓴다.
   - `reason="{}"`, `userAgent="{}"`
   - 외부에서 온 값(요청 경로, 헤더, 외부 응답)은 `LogValues.quoted()`를 거쳐 따옴표 안에 넣는다. 큰따옴표·역슬래시·개행으로 값을 끝내거나 줄을 나눠 키를 끼워 넣지 못하게 한다. 코드에 고정된 문자열은 그대로 쓴다.
6. **이벤트 이름과 중복되는 키는 쓰지 않는다.** 예: `event=AUTHENTICATION_REJECTED result=REJECTED` 에서 `result`는 불필요하다.
7. **객체를 통째로 찍지 않는다.** DTO의 `toString()` 대신 필요한 필드만 키로 꺼낸다.
8. **반복문 안에서 진행 상황을 INFO 이상으로 찍지 않는다.** 처리 건수는 반복이 끝난 뒤 `count=` 로 한 번 남긴다. 단, 반복 안에서 처리한 건마다 일어난 비즈니스 이벤트(완료·보류 등)와 실패는 대상 ID와 함께 남긴다.
9. **트랜잭션 안의 비즈니스 이벤트 로그는 커밋 뒤에 남긴다.** `AfterCommit.run(() -> log.info(...))`으로 감싸 롤백되면 남지 않게 한다. 로그에 쓸 값은 람다 밖에서 미리 꺼낸다. 실패·예외 로그는 롤백과 관계없이 남아야 하므로 바로 남긴다. 작업이 예외를 던지면 `AFTER_COMMIT_ACTION_FAILED`(WARN)로 남기고 호출한 쪽으로 전파하지 않는다.

## 4. 표준 키 사전

같은 의미에는 항상 같은 키를 쓴다. 표에 없는 키가 필요하면 이 표에 먼저 추가한 뒤 사용한다.

| 키 | 의미 | 값 예시 | 비고 |
| --- | --- | --- | --- |
| `event` | 이벤트 이름 | `ORDER_CREATED` | 필수 |
| `userId` | 요청을 수행한 로그인 사용자 식별자 | `99` | 관리자도 포함. 대상 회원·소유자 ID를 대신 넣지 않는다 |
| `targetUserId` | 처리 대상 회원 또는 데이터 소유자 식별자 | `10` | 요청자와 대상 회원이 다르거나, 요청자를 알 수 없는 공통 서비스·자동 처리에서 사용 |
| `<대상>Id` | 대상 식별자 | `orderId=A1024` | `orderId`, `paymentId` 등 |
| `result` | 처리 결과 | `SUCCESS`, `FAILURE` | 성공/실패를 한 이벤트로 묶을 때만. 결과가 enum이면 `name()` 사용 |
| `errorCode` | 내부 에러 코드 | `TOKEN_EXPIRED` | enum `name()` 사용 |
| `reason` | 사람이 읽는 원인 설명 | `"한도 초과"` | 따옴표 필수 |
| `durationMs` | 소요 시간 (밀리초) | `42` | 단위는 항상 ms |
| `count` | 처리 건수 | `120` | 배치·반복 처리 후 |
| `method` | HTTP 메서드 | `POST` |  |
| `uri` | 요청 경로 | `"/api/orders"` | `getRequestURI()`, 쿼리스트링 제외. 요청 경로는 사용자가 보낸 값이라 `=` 등이 들어갈 수 있어 `uri="{}"` + `LogValues.quoted()`. 외부 API 호출은 코드에 고정된 경로 템플릿(`/payments/{pgId}`)이라 따옴표 없이 쓴다 |
| `httpStatus` | HTTP 상태 코드 | `401` |  |
| `target` | 외부 연동 대상 | `payment-gateway` | 외부 API, DB, 메시지 큐 등 |
| `retry` | 재시도 횟수 | `2` | 첫 시도는 세지 않는다. 재시도 예정 로그는 이어서 할 재시도 순번(첫 시도 실패 시 `1`), 소진 로그는 실제로 한 재시도 횟수 |
| `amount` | 금액 (원) | `30000` | 원장 기준 금액 |
| `pgAmount` | PG가 확인한 금액 (원) | `30000` | `amount`와 비교할 때 |
| `currentCancellableAmount` | PG 취소 가능 잔액 (원) | `20000` | 부분 취소 요청 시 |
| `pgCancelledAmount` | PG가 확인한 취소 누적 금액 (원) | `20000` | 환불 원장과 맞출 때 |
| `idempotencyKey` | 외부 API 멱등 키 | `3f2a…` | PG 문의·중복 확인용 |
| `status` | 대상의 현재 상태 | `REQUESTED` | enum `name()` 사용 |
| `eventType` | 외부 이벤트 종류 | `WebhookTransactionPaid` | 웹훅 등 외부에서 받은 이벤트 |
| `questionLength` | 질문 글자 수 | `14` | 질문 원문 대신 남긴다 (8장) |
| `intent` | 질문 의도 | `HEIGHT_LIMIT` | enum `name()` 사용 |
| `attractionConfidence` | 어트랙션 분류 확신도 | `0.85` | 0~1 |
| `intentConfidence` | 의도 분류 확신도 | `0.73` | 0~1 |
| `model` | 외부 AI 모델 이름 | `"jev-1.13.0"` | 외부 응답 값이라 `model="{}"` + `LogValues.quoted()` |
| `inputTokens` | 외부 AI 입력 토큰 수 | `120` | 비용 집계용. 응답에 값이 없으면 `null` |
| `exceptionType` | 예외 클래스 이름 | `TypeSafeApiTimeoutException` | 예외 메시지에 민감정보나 외부 응답 본문이 섞일 수 있어 예외 객체 대신 남길 때 |

**요청자와 대상 회원 구분**

- 모든 도메인에서 요청한 사람은 `userId`, 처리 대상 회원은 `targetUserId`로 기록한다. 관리자 요청도 `adminId` 대신 `userId`를 사용한다.
- 본인 요청임을 확인한 로그는 `userId`만 기록해도 된다. 서로 다른 회원을 대신 처리하면, 이미 알고 있는 대상 회원 ID를 `targetUserId`로 함께 기록한다.
- 환불 공통 서비스, 결제 웹훅, 티켓 발급, 스케줄러처럼 소유자만 알고 있는 곳은 `targetUserId`를 사용한다. 소유자를 요청자로 추정하거나 임의의 `userId`를 넣지 않는다.
- 대상 회원 ID를 얻기 위한 추가 DB 조회나 로그만을 위한 API 응답 변경은 하지 않는다. 이미 알고 있는 대상 데이터 ID와 `traceId`로 연결한다.
- 이 규칙은 로그 키에 적용한다. 메서드 파라미터·DTO·이벤트 필드 이름까지 일괄 변경하지 않는다.

```text
event=ATTRACTION_UPDATED attractionId=3 userId=99
event=USER_STATUS_CHANGED userId=99 targetUserId=10 previousStatus=ACTIVE currentStatus=SUSPENDED
event=REFUND_REQUESTED refundId=20 pgId=pg-1 targetUserId=10 amount=30000
```

로그 키 변경 이전 기록에는 `adminId` 또는 소유자를 뜻하는 `userId`가 남아 있다. 과거 기록을 조회할 때는 해당 이벤트와 배포 시점을 함께 확인하고, 기존 Loki 쿼리·대시보드도 새 키에 맞춘다.

## 5. 로그 레벨 기준

레벨은 "누구 잘못인가, 사람이 봐야 하는가"로 고른다. 운영 환경 기본 레벨은 INFO다.

| 레벨 | 기준 | 예시 |
| --- | --- | --- |
| ERROR | 서버 쪽 원인으로 요청이 실패했고, 사람이 확인해야 함 | DB 연결 실패, 처리하지 못한 예외, 외부 API 최종 실패 |
| WARN | 실패는 아니지만 방치하면 문제가 될 위험 신호 | 재시도 발생, 응답 지연, 한도 근접, 폴백 동작 |
| INFO | 비즈니스상 의미 있는 사건, 또는 클라이언트 원인의 정상적인 실패 | 주문 생성, 결제 완료, 인증 거부, 입력값 검증 실패, 배치 시작/종료 |
| DEBUG | 개발·장애 분석용 상세 흐름 | 분기 결과, 중간 계산값 |
| TRACE | 사용하지 않음 |  |

- 4xx 응답(인증 실패, 검증 실패 등)은 원칙적으로 **INFO**다. 공격 탐지는 로그 레벨이 아니라 Loki 집계(10장)로 한다.
- ERROR 로그는 알림 대상이므로, "알림이 와도 할 일이 없는" 상황이면 ERROR가 아니다.
- 외부 API **최종 실패**는 도메인의 재시도까지 모두 소진한 시점이다(예: `*_RETRY_EXHAUSTED`). 외부 연동 클라이언트의 호출 한 번 실패는 다음 재시도로 회복될 수 있으므로 WARN이다.

## 6. 예외 로깅

예외는 객체째 마지막 인자로 넘기고, 처리하는 곳에서 한 번만 남긴다.

```
log.error("event=PAYMENT_FAILED orderId={} errorCode={}", orderId, errorCode.name(), e);  // O
log.error("event=PAYMENT_FAILED reason=" + e.getMessage());                                // X
```

- **마지막 인자로 예외 객체**를 넘긴다. 플레이스홀더 수보다 인자가 하나 많으면 SLF4J가 마지막 인자를 예외로 처리해 스택 트레이스를 출력한다.
- **`e.getMessage()`만 찍지 않는다.** 원인 추적이 불가능해진다.
- **로그를 남기고 다시 throw하지 않는다.** 같은 예외가 여러 번 기록된다. 다시 던질 때는 원인을 감싸서(`new XxxException(..., e)`) 던지고 로그는 남기지 않는다.
  - 예외: 외부 연동 클라이언트는 연동 맥락(대상 ID, 소요 시간, 외부 오류 타입)과 원인 예외를 한 번 남기고 도메인 예외로 바꿔 던질 수 있다. 이때 던지는 예외의 `errorCode`를 로그에 함께 남기고, 받는 쪽은 그 예외의 스택 트레이스를 다시 남기지 않고 `errorCode`만 남긴다.
- **예상된 비즈니스 예외**(검증 실패, 권한 없음 등)는 스택 트레이스 없이 `errorCode`만 INFO로 남긴다.
- **처리되지 않은 예외**는 `@RestControllerAdvice` 전역 핸들러에서 ERROR로 한 번 남긴다.

## 7. 로그에 포함할 데이터

로그 한 줄만 보고 "무슨 일이, 누구에게·무엇에 대해 일어났고, 결과가 어땠는지" 답할 수 있어야 한다. 같은 요청의 다른 로그에 있는 값은 `traceId`로 연결되므로 반복하지 않는다. 단, 스케줄러·비동기 작업처럼 traceId가 없을 수 있는 흐름은 9장 설정으로 traceId가 생기게 하고, 그럴 수 없으면 대상 ID를 각 로그에 반복해 남긴다.

**로그가 답해야 하는 질문**

| 질문 | 키 | 포함 기준 |
| --- | --- | --- |
| 무슨 일이 일어났나 | `event` | 항상 |
| 어느 요청인가 | `traceId` | MDC 자동 (스케줄러·비동기는 9장 참고) |
| 누가 | `userId` | 인증된 요청 경계에서 기록. 공통 서비스·자동 처리에서 요청자를 모르면 생략 |
| 누구를 대상으로 | `targetUserId` | 요청자와 다른 대상 회원 또는 소유자만 알고 있을 때. 추가 조회 없이 알고 있는 ID를 사용 |
| 무엇에 대해 | `<대상>Id` | 특정 대상이 있으면 항상 |
| 결과는 | `result`, `errorCode` | 실패 시 `errorCode` 필수. 대응하는 ErrorCode가 없는 실패(DB 오류, 수동 확인 알림 등)는 `reason`이나 예외 객체로 원인을 남긴다 |
| 왜 | `reason` | `errorCode`만으로 원인을 알 수 없을 때 |
| 얼마나 | `durationMs`, `count` | 외부 호출, 배치, 오래 걸리는 처리 |
| 어디서 | `method`, `uri`, `httpStatus` | HTTP 요청 경계에서 남기는 로그 |

**로그 유형별 필수·권장 키**

| 로그 유형 | 레벨 | 필수 키 | 권장 키 |
| --- | --- | --- | --- |
| 비즈니스 이벤트 (주문 생성, 결제 완료) | INFO | `event`, `<대상>Id`, `userId`(요청자를 아는 경우) | `targetUserId`(대상 회원·소유자를 아는 경우), 금액 등 핵심 수치 |
| 클라이언트 원인 실패 (4xx) | INFO | `event`, `method`, `uri`, `errorCode`, `httpStatus` | `userId` |
| 서버 오류 (5xx, 처리되지 않은 예외) | ERROR | `event`, `errorCode`(대응하는 ErrorCode가 있을 때), `<대상>Id`, 예외 객체 | `reason` |
| 외부 연동 호출 | INFO | `event`, `target`, `durationMs`, `result` | `httpStatus` |
| 재시도·지연 등 위험 신호 | WARN | `event`, `target`, `retry` 또는 `durationMs` | `reason` |
| 배치·스케줄 작업 | INFO | `event`, `count`, `durationMs` | 실패 건수. 결과는 이벤트 이름(`_COMPLETED`·`_FAILED`)으로 나누고 `result`는 쓰지 않는다 |

**포함하지 않는 데이터**

- 공통 필드(`ts`, `level`, `logger`, `thread`, `traceId`): 출력 패턴이 붙이므로 메시지에 다시 쓰지 않는다.
- 민감정보와 요청/응답 바디 전체: 8장 기준을 따른다.
- 계산 비용이 큰 값: 필요하면 DEBUG로 남기고 `isDebugEnabled()`로 감싼다.
- 검색·집계에 쓰지 않을 값: "이 값으로 무엇을 찾을 것인가"에 답할 수 없으면 넣지 않는다.

## 8. 민감정보

아래 정보는 어떤 레벨에서도 로그에 남기지 않는다. 사용자를 식별해야 할 때는 내부 ID를 `userId` 또는 `targetUserId`로만 기록한다.

| 분류 | 항목 | 처리 |
| --- | --- | --- |
| 인증 정보 | 비밀번호, 액세스/리프레시 토큰, API 키, 세션 ID, `Authorization` 헤더 | 절대 기록 금지 |
| 금융 정보 | 카드번호, 계좌번호 | 절대 기록 금지 |
| 고유식별정보 | 주민등록번호, 여권번호, 운전면허번호 | 절대 기록 금지 |
| 개인정보 | 이름, 이메일, 전화번호, 주소 | 기록하지 않음, 꼭 필요하면 마스킹 (`010-****-1234`) |
| 요청 원문 | 요청/응답 바디 전체, 쿼리스트링 | 기록하지 않음, 필요한 필드만 키로 추출 |

- `uri` 키에는 `getRequestURI()`를 사용한다. `getRequestURL()`이나 쿼리스트링에는 토큰이 섞일 수 있다.
- 코드 리뷰에서 DTO·엔티티를 그대로 로그 인자로 넘기는 코드는 반려한다.

## 9. 요청 추적: traceId와 MDC

`traceId`는 Micrometer Tracing이 MDC에 자동으로 넣으며, 개발자가 메시지에 직접 쓰지 않는다.

```groovy
// build.gradle (Spring Boot 4.x)
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'org.springframework.boot:spring-boot-micrometer-tracing-brave'
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
```

- 의존성을 추가하면 `traceId`, `spanId`가 MDC에 들어가고, 2장의 출력 패턴이 `traceId`를 자동으로 출력한다.
- Spring Boot 4는 자동 설정이 모듈로 나뉘어 있어 두 의존성이 모두 필요하다. `spring-boot-micrometer-tracing-brave`는 Tracer 자동 설정을, `micrometer-tracing-bridge-brave`는 Brave 구현과 MDC 연동(`brave-context-slf4j`)을 담당하며, 모듈이 브리지를 함께 가져오지 않는다. 버전은 Spring Boot BOM이 관리하므로 명시하지 않는다.
- 요청 단위 공통 값(예: 인증된 `userId`)을 MDC에 넣을 경우, 필터나 인터셉터에서 `MDC.put()` 하고 `finally` 블록에서 반드시 `MDC.remove()` 한다. 스레드 풀이 재사용되므로 정리하지 않으면 다른 요청에 값이 섞인다.
- `@Async`, 별도 스레드 풀, CompletableFuture에서는 MDC와 traceId가 자동 전파되지 않는다. ContextPropagatingTaskDecorator(Spring 6.1+)를 실행기에 등록해 전파하며, 직접 만든 스레드는 traceId 없이 출력된다는 점을 감안한다. @Scheduled 작업은 Spring 6.1+에서 실행마다 새 traceId가 생성되고, 그 이전 버전이나 Quartz 등은 작업 시작 시 MDC에 직접 넣는다.

## 10. Loki 수집과 조회

레이블은 값의 종류가 적은 것만 붙이고, 나머지는 조회할 때 `| logfmt`로 꺼낸다.

**레이블 규칙**

| 구분 | 키 | 이유 |
| --- | --- | --- |
| 레이블로 사용 | `app`, `env` (+ 필요 시 `level`) | 값 종류가 적어 스트림 수가 작게 유지됨 |
| 레이블로 사용 | `instance` (EC2 인스턴스 ID) | 인스턴스별로 스트림을 나눠, 한 인스턴스의 전송이 늦어져도 다른 인스턴스 로그 때문에 거부(`too far behind`)되지 않게 한다. 값은 배포마다 바뀌지만 동시에 떠 있는 인스턴스 수만큼만 생긴다 |
| 레이블 금지 | `userId`, `orderId`, `traceId`, `uri`, `event` 등 | 값이 많아 스트림이 폭증하고 Loki 성능이 저하됨 |

**수집기 설정**

- 수집기는 Grafana Alloy를 사용한다. (Promtail은 지원 종료 수순) 설정은 `deploy/alloy/config.alloy`에 둔다.
- 애플리케이션은 로그를 stdout으로만 출력하고 파일로 쓰지 않는다. Alloy는 Docker API(`loki.source.docker`)로 compose의 `app` 서비스 컨테이너 stdout만 수집한다.
- Alloy는 Docker 소켓을 직접 마운트하지 않고, 읽기 전용 프록시(`docker-proxy`)로 Docker API에 접근한다. 소켓은 `:ro`로 마운트해도 API 쓰기를 막지 못한다.
- stdout에는 logfmt 로그만 나가게 한다. JVM이 직접 찍는 줄(`JAVA_TOOL_OPTIONS`의 `Picked up ...` 등)이 섞이지 않도록 JVM 옵션은 실행 명령에 직접 넘긴다.
- 컨테이너 로그는 Docker `json-file` 드라이버의 `max-size`, `max-file`로 순환해 디스크가 차지 않게 한다.
- 스택 트레이스가 한 로그로 묶이도록 multiline 설정에서 "`ts=`로 시작하는 줄이 새 로그의 시작"으로 지정한다. (첫 줄 정규식 예: `^ts=`)

**조회 예시 (LogQL)**

```logql
# 특정 이벤트 조회
{app="order-api", env="prod"} | logfmt | event="ORDER_CREATED"

# 하나의 요청 전체 추적
{app=~".+", env="prod"} | logfmt | traceId="4bf92f3577b34da6"

# 5분 단위 인증 거부 건수를 errorCode별로 집계
sum by (errorCode) (
  count_over_time({app="auth-api", env="prod"} | logfmt | event="AUTHENTICATION_REJECTED" [5m])
)

# 외부 연동 평균 소요 시간
avg_over_time({app="order-api"} | logfmt | event="EXTERNAL_API_CALLED" | unwrap durationMs [5m])
```

## 11. 상황별 예시와 리뷰 체크리스트

**상황별 예시**

```
// 비즈니스 이벤트
log.info("event=ORDER_CREATED orderId={} userId={} amount={}", orderId, userId, amount);

// 클라이언트 원인 실패 (4xx)
log.info("event=AUTHENTICATION_REJECTED method={} uri=\"{}\" errorCode={} httpStatus={}",
        request.getMethod(), LogValues.quoted(request.getRequestURI()), errorCode.name(), errorCode.getStatus().value());

// 외부 연동 호출
log.info("event=EXTERNAL_API_CALLED target={} durationMs={} result={} httpStatus={}", "payment-gateway", ms, result, status);

// 위험 신호
log.warn("event=EXTERNAL_API_RETRIED target={} retry={} reason=\"{}\"", "payment-gateway", retry, reason);

// 서버 오류 (예외는 마지막 인자)
log.error("event=PAYMENT_FAILED orderId={} errorCode={}", orderId, errorCode.name(), e);

// 배치 처리
log.info("event=SETTLEMENT_BATCH_COMPLETED count={} durationMs={}", count, ms);
```

**코드 리뷰 체크리스트**

- [ ] 메시지가 `event=`로 시작하고, 이벤트 이름이 `대상_동작` 대문자 스네이크 케이스인가
- [ ] 나머지가 모두 `key=value`이고, 키 이름이 표준 키 사전과 일치하는가
- [ ] `userId`는 요청자, `targetUserId`는 대상 회원·소유자이며, 소유자를 요청자로 기록하지 않았는가
- [ ] 가변 값을 `{}` 플레이스홀더로 넘겼는가
- [ ] 공백이 들어갈 수 있는 값을 따옴표로 감쌌는가
- [ ] 레벨이 5장 기준에 맞는가 (4xx는 INFO)
- [ ] 예외를 마지막 인자로 넘겼고, 같은 예외를 중복 기록하지 않는가
- [ ] 민감정보나 DTO 전체를 찍지 않는가
- [ ] 반복문 안에서 진행 상황을 INFO 이상으로 찍지 않는가 (건별 비즈니스 이벤트·실패는 허용)
- [ ] 트랜잭션 안의 비즈니스 이벤트 로그를 커밋 뒤에 남기는가
