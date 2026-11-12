# 결제·PortOne API

[전체 API 목록](README.md) · [공통 규칙](common.md)

결제 목록·상세·승인과 프론트 설정 조회, PortOne 웹훅 수신을 포함한다. 숫자 paymentId와 문자열 pgId를 구분한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/payments` | 내 결제 목록 | 로그인 | 200 |
| GET | `/api/payments/{paymentId}` | 내 결제 상세 | 로그인 | 200 |
| POST | `/api/payments/{pgId}/confirm` | 결제 승인 확인 | 로그인 | 200 |
| GET | `/api/config/portone` | PortOne 프론트 설정 | 로그인 | 200 |
| POST | `/api/payments/webhooks/portone` | PortOne 웹훅 수신 | PG 서명 | 200 |

## 내 결제 목록

```http
GET /api/payments
```

- 접근: **로그인**.
- 성공: **200**. `data`: [PageableDto<PaymentResponse>](#schema-pageabledto).
- 요청 본문: 없음.
- Query: paymentStatus: PaymentStatus, 선택.
- Pagination: `page`, `size`, 반복 가능한 `sort` 사용. [공통 pagination](common.md#pagination) 참조.

paymentStatus 선택 조건. 기본 page=0, size=10. 정렬 생략 시 Repository가 createdAt DESC, id DESC를 적용한다. 허용 sort: createdAt, amount, paidAt. 지정 정렬 뒤에도 id DESC를 추가한다.

## 내 결제 상세

```http
GET /api/payments/{paymentId}
```

- 접근: **로그인**.
- 성공: **200**. `data`: [PaymentDetailResponse](#schema-paymentdetailresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

paymentId는 DB의 숫자 결제 ID. 없거나 다른 회원 결제이면 404 PAYMENT_NOT_FOUND. order.orderItems는 주문 상세 API의 items와 이름·구조가 다르다.

## 결제 승인 확인

```http
POST /api/payments/{pgId}/confirm
```

- 접근: **로그인**.
- 성공: **200**. `data`: [ConfirmPaymentResponse](#schema-confirmpaymentresponse).
- 요청 본문: [ConfirmPaymentRequest](#schema-confirmpaymentrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

pgId는 주문 생성 응답의 PG 문자열 식별자. PG 상태·금액·본인 주문을 서버에서 확인한다. 클라이언트 결제창 성공만으로 티켓 발급 완료라고 판단하지 않는다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "orderId": 1
}
```

## PortOne 프론트 설정

```http
GET /api/config/portone
```

- 접근: **로그인**.
- 성공: **200**. `data`: [PortOneConfigResponse](#schema-portoneconfigresponse).
- 요청 본문: 없음.

storeId, channelKey를 반환한다. 서버 API secret·웹훅 secret을 응답하지 않는다.

## PortOne 웹훅 수신

```http
POST /api/payments/webhooks/portone
```

- 접근: **PG 서명**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 서명된 원본 JSON. 아래 설명 참조.

원본 JSON 본문과 webhook-id, webhook-signature, webhook-timestamp 헤더가 필수다. 사용자 JWT 대신 SDK 서명·시각 검증을 사용한다. 검증 후 수신 기록을 저장하고 200을 반환하며 결제 반영은 비동기다. 동일 webhook-id 재수신도 200. 잘못된 서명·누락 헤더는 401 PAYMENT_WEBHOOK_INVALID_SIGNATURE.

## 웹훅 요청 형태

```http
POST /api/payments/webhooks/portone
Content-Type: application/json
webhook-id: <webhook-id>
webhook-signature: <signed-value>
webhook-timestamp: <unix-seconds>
```

본문은 PortOne이 서명한 원문을 보낸다. 이 endpoint는 프론트가 임의로 결제 완료를 알리는 API가 아니다. SDK의 외부 이벤트 전체 schema를 프로젝트가 자체 DTO로 정의하지 않으므로 예시 서명이나 이벤트를 조작해 정상 요청으로 안내하지 않는다.

## 결제 승인과 비동기 보정

HTTP 성공·실패와 PG의 최종 결제 결과가 항상 동시에 확정되는 것은 아니다. 웹훅·동기화·보상 취소가 별도로 실행될 수 있다. 기존 상세 설계는 [결제 승인](../payment/payment-approval.md), [보상 처리](../payment/payment-compensation.md), [웹훅 처리](../payment/payment-webhook-plan.md)를 참고한다.

## 대표 성공 응답 예시

다음은 `GET /api/payments/{paymentId}`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "paymentId": 1,
    "amount": 50000,
    "status": "PAID",
    "paidAt": "2026-11-13T01:00:00Z",
    "createdAt": "2026-11-13T01:00:00Z",
    "order": {
      "orderId": 1,
      "totalAmount": 50000,
      "status": "COMPLETED",
      "createdAt": "2026-11-13T01:00:00Z",
      "orderItems": [
        {
          "orderItemId": 1,
          "ticketProductId": 1,
          "ticketProductName": "예시",
          "price": 50000,
          "quantity": 1
        }
      ]
    }
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-pageabledto"></a>

### PageableDto

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<T>` | — | 현재 페이지 항목 목록 |
| `page` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |
| `hasNext` | `boolean` | — | 다음 페이지 존재 여부 |

근거: [PageableDto.java](../../src/main/java/com/example/attractionreservation/global/response/PageableDto.java).

<a id="schema-paymentresponse"></a>

### PaymentResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `paymentId` | `Long` | — | DB 결제 ID |
| `amount` | `int` | — | 금액(원) |
| `status` | `PaymentStatus` | — | 상태 |
| `paidAt` | `Instant` | — | 결제 시각 |

근거: [PaymentResponse.java](../../src/main/java/com/example/attractionreservation/domain/payment/dto/PaymentResponse.java).

<a id="schema-paymentdetailresponse"></a>

### PaymentDetailResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `paymentId` | `Long` | — | DB 결제 ID |
| `amount` | `int` | — | 금액(원) |
| `status` | `PaymentStatus` | — | 상태 |
| `paidAt` | `Instant` | — | 결제 시각 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `order` | [OrderResponse](#schema-orderresponse) | — | 결제와 연결된 주문 |

근거: [PaymentDetailResponse.java](../../src/main/java/com/example/attractionreservation/domain/payment/dto/PaymentDetailResponse.java).

<a id="schema-orderresponse"></a>

### OrderResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `totalAmount` | `int` | — | 주문 총액(원) |
| `status` | `OrderStatus` | — | 상태 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `orderItems` | `List<OrderItemResponse>` | — | 결제 상세의 주문 항목 목록 |

근거: [OrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/OrderResponse.java).

<a id="schema-orderitemresponse"></a>

### OrderItemResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderItemId` | `Long` | — | 주문 항목 ID |
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `ticketProductName` | `String` | — | 상품 이름 |
| `price` | `int` | — | 상품 또는 주문 시점 단가(원) |
| `quantity` | `int` | — | 수량 |

근거: [OrderItemResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/OrderItemResponse.java).

<a id="schema-confirmpaymentrequest"></a>

### ConfirmPaymentRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | 필수 | 주문 ID |

근거: [ConfirmPaymentRequest.java](../../src/main/java/com/example/attractionreservation/domain/payment/dto/ConfirmPaymentRequest.java).

<a id="schema-confirmpaymentresponse"></a>

### ConfirmPaymentResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `orderId` | `Long` | — | 주문 ID |
| `pgId` | `String` | — | PG 결제 식별자(문자열) |
| `amount` | `int` | — | 금액(원) |
| `status` | `PaymentStatus` | — | 상태 |
| `paidAt` | `Instant` | — | 결제 시각 |

근거: [ConfirmPaymentResponse.java](../../src/main/java/com/example/attractionreservation/domain/payment/dto/ConfirmPaymentResponse.java).

<a id="schema-portoneconfigresponse"></a>

### PortOneConfigResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `storeId` | `String` | — | PortOne 상점 식별자 |
| `channelKey` | `String` | — | PortOne 결제 채널 키 |

근거: [PortOneConfigResponse.java](../../src/main/java/com/example/attractionreservation/infra/portone/dto/PortOneConfigResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `PaymentStatus` | `PENDING`, `PAID`, `PARTIALLY_REFUNDED`, `REFUNDED`, `FAILED`, `CANCELLED` |
| `OrderStatus` | `PENDING`, `COMPLETED`, `CANCELLED`, `PARTIALLY_REFUNDED`, `REFUNDED` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 400 | `INVALID_INPUT_VALUE` | 입력값을 확인해주세요 |
| 400 | `INVALID_SORT_PROPERTY` | 지원하지 않는 정렬 조건입니다. |
| 404 | `PAYMENT_NOT_FOUND` | 결제 내역을 찾을 수 없습니다. |
| 409 | `PAYMENT_NOT_PAID` | 결제사에서 결제가 완료되지 않았습니다. |
| 400 | `PAYMENT_INCORRECT_AMOUNT` | 결제 금액이 결제 원장과 일치하지 않습니다. |
| 502 | `PAYMENT_GATEWAY_ERROR` | 결제사 조회에 실패했습니다. |
| 404 | `PAYMENT_PG_PAYMENT_NOT_FOUND` | PG 결제를 찾을 수 없습니다. |
| 409 | `PAYMENT_ALREADY_PROCESSED` | 이미 처리된 결제입니다. |
| 409 | `PAYMENT_ALREADY_CANCELLED` | 이미 취소된 결제입니다. |
| 409 | `PAYMENT_PG_FAILED` | 결제가 실패했습니다. 결제 수단을 확인한 뒤 다시 결제해 주세요. |
| 401 | `PAYMENT_WEBHOOK_INVALID_SIGNATURE` | 웹훅 서명이 유효하지 않습니다. |

## 구현 근거

[PaymentController.java](../../src/main/java/com/example/attractionreservation/domain/payment/controller/PaymentController.java), [PortOneConfigController.java](../../src/main/java/com/example/attractionreservation/infra/portone/controller/PortOneConfigController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
