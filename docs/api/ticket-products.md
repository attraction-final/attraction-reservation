# 티켓 상품 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

판매용 TicketProduct를 관리한다. 개별 발급 입장권 Ticket과 구분한다. 조회도 현재 SecurityConfig상 로그인해야 한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/ticket-products` | 상품 목록 | 로그인 | 200 |
| GET | `/api/ticket-products/{ticketProductId}` | 상품 상세 | 로그인 | 200 |
| POST | `/api/admin/ticket-products` | 상품 등록 | ADMIN | 201 |
| PUT | `/api/admin/ticket-products/{ticketProductId}` | 상품 수정 | ADMIN | 200 |
| DELETE | `/api/admin/ticket-products/{ticketProductId}` | 상품 삭제 | ADMIN | 200 |

## 상품 목록

```http
GET /api/ticket-products
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchTicketProductListResponse](#schema-searchticketproductlistresponse).
- 요청 본문: 없음.
- Query: [SearchTicketProductCondition](#schema-searchticketproductcondition).
- Pagination: `page`, `size`, 반복 가능한 `sort` 사용. [공통 pagination](common.md#pagination) 참조.

name 부분 일치, type/status 일치 검색. DELETED 상품은 기본 조회에서 제외한다. 기본 sort=type,desc이며 id 정렬이 없으면 id,desc를 추가한다. 허용 정렬: id, name, type, price, description, stock, status, createdAt, updatedAt.

## 상품 상세

```http
GET /api/ticket-products/{ticketProductId}
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchTicketProductResponse](#schema-searchticketproductresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

수정할 때 응답의 version 값을 보관해 PUT 요청에 전달한다. 목록은 DELETED를 제외하지만 현재 상세 조회는 findById를 사용하여 DELETED 상품도 반환할 수 있다. 목록과 동일한 제외 정책이 필요한지는 팀 확인 대상이다.

## 상품 등록

```http
POST /api/admin/ticket-products
```

- 접근: **ADMIN**.
- 성공: **201**. `data`: [CreateTicketProductResponse](#schema-createticketproductresponse).
- 요청 본문: [CreateTicketProductRequest](#schema-createticketproductrequest) (`application/json`).

price·stock은 0 이상. 종류 ALL_DAY/AFTERNOON. 상품별 재고는 방문일별 재고가 아닌 전체 날짜 통합 수량이다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "name": "종일권",
  "type": "ALL_DAY",
  "price": 50000,
  "description": "상품 또는 시설 설명",
  "saleStatus": "ON_SALE",
  "stock": 1000
}
```

## 상품 수정

```http
PUT /api/admin/ticket-products/{ticketProductId}
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateTicketProductResponse](#schema-updateticketproductresponse).
- 요청 본문: [UpdateTicketProductRequest](#schema-updateticketproductrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

요청 필드는 모두 필수이며 type은 변경하지 않는다. version 불일치는 409 CONCURRENT_UPDATE_CONFLICT. saleStatus=DELETED는 거부하며 삭제 API를 사용한다. stock은 절대 수량이다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "name": "종일권",
  "price": 50000,
  "description": "상품 또는 시설 설명",
  "saleStatus": "ON_SALE",
  "stock": 1000,
  "version": 0
}
```

## 상품 삭제

```http
DELETE /api/admin/ticket-products/{ticketProductId}
```

- 접근: **ADMIN**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

DELETED 상태로 처리한다. 이미 발급된 티켓을 삭제하는 API가 아니다.

## 대표 성공 응답 예시

다음은 `GET /api/ticket-products`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "ticketProductId": 1,
        "name": "종일권",
        "type": "ALL_DAY",
        "price": 50000,
        "description": "상품 또는 시설 설명",
        "status": "ON_SALE",
        "stock": 1000,
        "version": 0
      }
    ],
    "number": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-searchticketproductlistresponse"></a>

### SearchTicketProductListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<SearchTicketProductResponse>` | — | 현재 페이지 항목 목록 |
| `number` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |

근거: [SearchTicketProductListResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/SearchTicketProductListResponse.java).

<a id="schema-searchticketproductresponse"></a>

### SearchTicketProductResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `name` | `String` | — | 이름 |
| `type` | `TicketType` | — | 유형 |
| `price` | `int` | — | 상품 또는 주문 시점 단가(원) |
| `description` | `String` | — | 설명 |
| `status` | `TicketProductStatus` | — | 상태 |
| `stock` | `int` | — | 상품 전체 잔여 재고 |
| `version` | `Long` | — | 동시 수정 검사용 버전 |

근거: [SearchTicketProductResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/SearchTicketProductResponse.java).

<a id="schema-searchticketproductcondition"></a>

### SearchTicketProductCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 선택 | 이름 |
| `type` | `TicketType` | 선택 | 유형 |
| `status` | `TicketProductStatus` | 선택 | 상태 |

근거: [SearchTicketProductCondition.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/SearchTicketProductCondition.java).

<a id="schema-createticketproductrequest"></a>

### CreateTicketProductRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 80 | 이름 |
| `type` | `TicketType` | 필수 | 유형 |
| `price` | `Integer` | 필수; 최소 0 | 상품 또는 주문 시점 단가(원) |
| `description` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 설명 |
| `saleStatus` | `TicketProductStatus` | 필수 | 상품 판매 상태 |
| `stock` | `Integer` | 필수; 최소 0 | 상품 전체 잔여 재고 |

근거: [CreateTicketProductRequest.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/CreateTicketProductRequest.java).

<a id="schema-createticketproductresponse"></a>

### CreateTicketProductResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `name` | `String` | — | 이름 |
| `type` | `TicketType` | — | 유형 |
| `price` | `int` | — | 상품 또는 주문 시점 단가(원) |
| `description` | `String` | — | 설명 |
| `saleStatus` | `TicketProductStatus` | — | 상품 판매 상태 |
| `stock` | `int` | — | 상품 전체 잔여 재고 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `updatedAt` | `Instant` | — | 수정 시각 |

근거: [CreateTicketProductResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/CreateTicketProductResponse.java).

<a id="schema-updateticketproductrequest"></a>

### UpdateTicketProductRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 80 | 이름 |
| `price` | `Integer` | 필수; 최소 0 | 상품 또는 주문 시점 단가(원) |
| `description` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 설명 |
| `saleStatus` | `TicketProductStatus` | 필수 | 상품 판매 상태 |
| `stock` | `Integer` | 필수; 최소 0 | 상품 전체 잔여 재고 |
| `version` | `Long` | 필수; 최소 0 | 동시 수정 검사용 버전 |

근거: [UpdateTicketProductRequest.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/UpdateTicketProductRequest.java).

<a id="schema-updateticketproductresponse"></a>

### UpdateTicketProductResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `name` | `String` | — | 이름 |
| `type` | `TicketType` | — | 유형 |
| `price` | `int` | — | 상품 또는 주문 시점 단가(원) |
| `description` | `String` | — | 설명 |
| `saleStatus` | `TicketProductStatus` | — | 상품 판매 상태 |
| `stock` | `int` | — | 상품 전체 잔여 재고 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `updatedAt` | `Instant` | — | 수정 시각 |
| `version` | `Long` | — | 동시 수정 검사용 버전 |

근거: [UpdateTicketProductResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/dto/UpdateTicketProductResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `TicketType` | `ALL_DAY`, `AFTERNOON` |
| `TicketProductStatus` | `ON_SALE`, `STOPPED`, `ENDED`, `DELETED`, `OUT_OF_STOCK` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 409 | `CONCURRENT_UPDATE_CONFLICT` | 다른 요청에 의해 데이터가 변경되었습니다. 다시 시도해주세요. |
| 400 | `INVALID_TICKET_PRODUCT_PRICE` | 티켓 상품 가격은 0원 이상이어야 합니다. |
| 400 | `INVALID_TICKET_PRODUCT_STOCK` | 티켓 상품 재고는 0개 이상이어야 합니다. |
| 400 | `INVALID_TICKET_PRODUCT_NAME` | 올바른 티켓 상품 이름을 입력해주세요. |
| 400 | `INVALID_TICKET_PRODUCT_DESCRIPTION` | 올바른 티켓 상품 설명을 입력해주세요. |
| 400 | `INVALID_TICKET_PRODUCT_TYPE` | 티켓 상품의 타입이 올바르지 않습니다. |
| 400 | `INVALID_TICKET_PRODUCT_STATUS` | 티켓 상품의 상태값이 올바르지 않습니다. |
| 404 | `TICKET_PRODUCT_NOT_FOUND` | 티켓 상품을 찾을 수 없습니다. |
| 409 | `TICKET_PRODUCT_ALREADY_DELETED` | 삭제된 티켓 상품은 수정할 수 없습니다. |
| 409 | `TICKET_PRODUCT_STOCK_INSUFFICIENT` | 티켓 상품 재고가 부족합니다. |

## 구현 근거

[AdminTicketProductController.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/controller/AdminTicketProductController.java), [TicketProductController.java](../../src/main/java/com/example/attractionreservation/domain/ticketProduct/controller/TicketProductController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
