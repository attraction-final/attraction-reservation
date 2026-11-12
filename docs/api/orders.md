# 주문 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

회원 주문과 관리자 대리 주문을 관리한다. 주문 생성 시 재고를 차감하고 결제 대기 데이터·pgId를 만들며 결제 승인과 티켓 발급은 별도다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| POST | `/api/orders` | 주문 생성 | 로그인 | 201 |
| GET | `/api/orders/me` | 내 주문 목록 | 로그인 | 200 |
| GET | `/api/orders/{orderId}` | 내 주문 상세 | 로그인 | 200 |
| POST | `/api/orders/{orderId}/cancel` | 주문 취소 | 로그인 | 200 |
| GET | `/api/admin/orders` | 전체 주문 목록 | ADMIN | 200 |
| GET | `/api/admin/orders/{orderId}` | 주문 상세 | ADMIN | 200 |
| POST | `/api/admin/orders` | 관리자 대리 주문 | ADMIN | 201 |
| POST | `/api/admin/orders/{orderId}/cancel` | 관리자 주문 취소 | ADMIN | 200 |

## 주문 생성

```http
POST /api/orders
```

- 접근: **로그인**.
- 성공: **201**. `data`: [CreateOrderResponse](#schema-createorderresponse).
- 요청 본문: [CreateOrderRequest](#schema-createorderrequest) (`application/json`).

본인 주문으로 저장한다. items 1개 이상, 상품 ID/수량 양수. 가격은 서버가 계산한다. 방문일은 서버 오늘부터 29일 후까지(오늘 포함 30일). 판매중·재고를 확인한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "items": [
    {
      "ticketProductId": 1,
      "quantity": 1
    }
  ],
  "visitDate": "2026-11-13"
}
```

## 내 주문 목록

```http
GET /api/orders/me
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchOrderListResponse](#schema-searchorderlistresponse).
- 요청 본문: 없음.
- Query: [SearchOrderCondition](#schema-searchordercondition).

기본 page=0, size=10. 생성 시각 최신순으로 조회한다. 응답 목록의 items는 상품명·수량 요약이며 상세 items와 구조가 다르다.

## 내 주문 상세

```http
GET /api/orders/{orderId}
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchOrderResponse](#schema-searchorderresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본인 주문만 조회. 다른 회원 주문은 403 ORDER_ACCESS_DENIED. items[].id는 주문 항목 ID다. pgId는 결제 승인·환불 경로에 사용한다.

## 주문 취소

```http
POST /api/orders/{orderId}/cancel
```

- 접근: **로그인**.
- 성공: **200**. `data`: [UpdateOrderResponse](#schema-updateorderresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본문 없음. 본인 PENDING 주문만 취소한다. 주문·결제 취소와 재고 복구를 수행한다. 결제 완료 주문은 환불 API를 사용한다.

## 전체 주문 목록

```http
GET /api/admin/orders
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAdminOrderListResponse](#schema-searchadminorderlistresponse).
- 요청 본문: 없음.
- Query: [SearchAdminOrderCondition](#schema-searchadminordercondition).

기본 page=0, size=10, size 최대100. createdAt DESC, id DESC.

## 주문 상세

```http
GET /api/admin/orders/{orderId}
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAdminOrderResponse](#schema-searchadminorderresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

회원 정보·주문 항목·발급 티켓 요약·pgId를 제공한다. tickets[].orderItemId는 주문 항목과 연결하는 값이다.

## 관리자 대리 주문

```http
POST /api/admin/orders
```

- 접근: **ADMIN**.
- 성공: **201**. `data`: [CreateOrderResponse](#schema-createorderresponse).
- 요청 본문: [CreateAdminOrderRequest](#schema-createadminorderrequest) (`application/json`).

userId 대상 회원은 ACTIVE여야 한다. 대상 회원 소유의 PENDING 주문과 결제를 만들며 결제 승인·티켓 발급을 완료하지 않는다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "userId": 1,
  "items": [
    {
      "ticketProductId": 1,
      "quantity": 1
    }
  ],
  "visitDate": "2026-11-13"
}
```

## 관리자 주문 취소

```http
POST /api/admin/orders/{orderId}/cancel
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateOrderResponse](#schema-updateorderresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

PENDING 주문만 취소한다. 결제 완료 후에는 관리자 환불 API를 사용한다.

## 대표 성공 응답 예시

다음은 `POST /api/orders`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "orderId": 1,
    "totalAmount": 50000,
    "status": "PENDING",
    "pgId": "example-payment-id",
    "items": [
      {
        "id": 1,
        "ticketProductId": 1,
        "price": 50000,
        "quantity": 1
      }
    ]
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-createorderrequest"></a>

### CreateOrderRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `items` | `List< Item>` | 필수; 빈 목록 불가 | 주문 항목 목록 |
| `visitDate` | `LocalDate` | 필수 | 주문 방문일 |

근거: [CreateOrderRequest.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/CreateOrderRequest.java).

<a id="schema-createorderrequest-item"></a>

### CreateOrderRequest.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketProductId` | `Long` | 필수; 최소 1 | 티켓 상품 ID |
| `quantity` | `Integer` | 필수; 최소 1 | 수량 |

근거: [CreateOrderRequest.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/CreateOrderRequest.java).

<a id="schema-createorderresponse"></a>

### CreateOrderResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `totalAmount` | `Integer` | — | 주문 총액(원) |
| `status` | `String` | — | 상태 |
| `pgId` | `String` | — | PG 결제 식별자(문자열) |
| `items` | `List<Item>` | — | 주문 항목 목록 |

근거: [CreateOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/CreateOrderResponse.java).

<a id="schema-createorderresponse-item"></a>

### CreateOrderResponse.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `price` | `Integer` | — | 상품 또는 주문 시점 단가(원) |
| `quantity` | `Integer` | — | 수량 |

근거: [CreateOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/CreateOrderResponse.java).

<a id="schema-searchorderlistresponse"></a>

### SearchOrderListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<Item>` | — | 현재 페이지 항목 목록 |
| `page` | `Integer` | — | 현재 페이지 번호(0부터) |
| `size` | `Integer` | — | 페이지 크기 |
| `totalElements` | `Long` | — | 전체 항목 수 |
| `totalPages` | `Integer` | — | 전체 페이지 수 |

근거: [SearchOrderListResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderListResponse.java).

<a id="schema-searchorderlistresponse-item"></a>

### SearchOrderListResponse.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `totalAmount` | `Integer` | — | 주문 총액(원) |
| `status` | `String` | — | 상태 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |
| `items` | `List<ItemSummary>` | — | 주문 항목 목록 |

근거: [SearchOrderListResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderListResponse.java).

<a id="schema-searchorderlistresponse-itemsummary"></a>

### SearchOrderListResponse.ItemSummary

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `ticketProductName` | `String` | — | 상품 이름 |
| `quantity` | `Integer` | — | 수량 |

근거: [SearchOrderListResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderListResponse.java).

<a id="schema-searchordercondition"></a>

### SearchOrderCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `page` | `Integer` | 생략 시 0; 최소 0 | 현재 페이지 번호(0부터) |
| `size` | `Integer` | 생략 시 10; 최소 1 | 페이지 크기 |

근거: [SearchOrderCondition.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderCondition.java).

<a id="schema-searchorderresponse"></a>

### SearchOrderResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `totalAmount` | `Integer` | — | 주문 총액(원) |
| `status` | `String` | — | 상태 |
| `visitDate` | `LocalDate` | — | 주문 방문일 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |
| `items` | `List<Item>` | — | 주문 항목 목록 |
| `pgId` | `String` | — | PG 결제 식별자(문자열) |

근거: [SearchOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderResponse.java).

<a id="schema-searchorderresponse-item"></a>

### SearchOrderResponse.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `price` | `Integer` | — | 상품 또는 주문 시점 단가(원) |
| `quantity` | `Integer` | — | 수량 |

근거: [SearchOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchOrderResponse.java).

<a id="schema-updateorderresponse"></a>

### UpdateOrderResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `status` | `OrderStatus` | — | 상태 |

근거: [UpdateOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/UpdateOrderResponse.java).

<a id="schema-searchadminorderlistresponse"></a>

### SearchAdminOrderListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<Item>` | — | 현재 페이지 항목 목록 |
| `page` | `Integer` | — | 현재 페이지 번호(0부터) |
| `size` | `Integer` | — | 페이지 크기 |
| `totalElements` | `Long` | — | 전체 항목 수 |
| `totalPages` | `Integer` | — | 전체 페이지 수 |

근거: [SearchAdminOrderListResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchAdminOrderListResponse.java).

<a id="schema-searchadminorderlistresponse-item"></a>

### SearchAdminOrderListResponse.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `userId` | `Long` | — | 대상 회원 ID |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `totalAmount` | `Integer` | — | 주문 총액(원) |
| `status` | `String` | — | 상태 |
| `visitDate` | `LocalDate` | — | 주문 방문일 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |

근거: [SearchAdminOrderListResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchAdminOrderListResponse.java).

<a id="schema-searchadminordercondition"></a>

### SearchAdminOrderCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `page` | `Integer` | 생략 시 0; 최소 0 | 현재 페이지 번호(0부터) |
| `size` | `Integer` | 생략 시 10; 최소 1; 최대 100 | 페이지 크기 |
| `userId` | `Long` | 선택; 양수 | 대상 회원 ID |
| `status` | `OrderStatus` | 선택 | 상태 |

근거: [SearchAdminOrderCondition.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchAdminOrderCondition.java).

<a id="schema-searchadminorderresponse"></a>

### SearchAdminOrderResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `orderId` | `Long` | — | 주문 ID |
| `userId` | `Long` | — | 대상 회원 ID |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `totalAmount` | `Integer` | — | 주문 총액(원) |
| `status` | `String` | — | 상태 |
| `visitDate` | `LocalDate` | — | 주문 방문일 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |
| `items` | `List<SearchOrderResponse.Item>` | — | 주문 항목 목록 |
| `tickets` | `List<TicketSummary>` | — | 발급 티켓 요약 |
| `pgId` | `String` | — | PG 결제 식별자(문자열) |

근거: [SearchAdminOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchAdminOrderResponse.java).

<a id="schema-searchadminorderresponse-ticketsummary"></a>

### SearchAdminOrderResponse.TicketSummary

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `orderItemId` | `Long` | — | 주문 항목 ID |
| `name` | `String` | — | 이름 |
| `status` | `String` | — | 상태 |
| `validDate` | `LocalDate` | — | 입장권 방문일 |

근거: [SearchAdminOrderResponse.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/SearchAdminOrderResponse.java).

<a id="schema-createadminorderrequest"></a>

### CreateAdminOrderRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `userId` | `Long` | 필수; 양수 | 대상 회원 ID |
| `items` | `List< Item>` | 필수; 빈 목록 불가 | 주문 항목 목록 |
| `visitDate` | `LocalDate` | 필수 | 주문 방문일 |

근거: [CreateAdminOrderRequest.java](../../src/main/java/com/example/attractionreservation/domain/order/dto/CreateAdminOrderRequest.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `OrderStatus` | `PENDING`, `COMPLETED`, `CANCELLED`, `PARTIALLY_REFUNDED`, `REFUNDED` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 404 | `USER_NOT_FOUND` | 유저를 찾을 수 없습니다. |
| 403 | `USER_NOT_ACTIVE` | 이용할 수 없는 회원 상태입니다. |
| 404 | `TICKET_PRODUCT_NOT_FOUND` | 티켓 상품을 찾을 수 없습니다. |
| 409 | `TICKET_PRODUCT_ALREADY_DELETED` | 삭제된 티켓 상품은 수정할 수 없습니다. |
| 409 | `TICKET_PRODUCT_STOCK_INSUFFICIENT` | 티켓 상품 재고가 부족합니다. |
| 409 | `ORDER_INVALID_STATUS_TRANSITION` | 유효하지 않은 상태 전이입니다. |
| 409 | `ORDER_PRODUCT_NOT_ON_SALE` | 판매 중인 티켓 상품만 주문할 수 있습니다. |
| 400 | `ORDER_AMOUNT_OUT_OF_RANGE` | 주문 금액이 허용 범위를 초과했습니다. |
| 404 | `ORDER_NOT_FOUND` | 주문을 찾을 수 없습니다. |
| 403 | `ORDER_CANCEL_ACCESS_DENIED` | 본인의 주문만 취소할 수 있습니다. |
| 409 | `ORDER_NOT_CANCELLABLE` | 결제 대기 상태의 주문만 취소할 수 있습니다. |
| 403 | `ORDER_ACCESS_DENIED` | 본인의 주문만 조회할 수 있습니다. |
| 400 | `ORDER_INVALID_VISIT_DATE` | 방문일은 오늘부터 30일 이내에서 선택해야 합니다. |
| 400 | `PAYMENT_INVALID_AMOUNT` | 유효하지 않은 결제 금액입니다. |

## 구현 근거

[AdminOrderController.java](../../src/main/java/com/example/attractionreservation/domain/order/controller/AdminOrderController.java), [OrderController.java](../../src/main/java/com/example/attractionreservation/domain/order/controller/OrderController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
