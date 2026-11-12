# 티켓 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

결제로 발급된 개별 입장권을 조회하고 관리자가 티켓 코드로 입장 처리한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/tickets/me` | 내 티켓 목록 | 로그인 | 200 |
| GET | `/api/tickets/{ticketId}` | 내 티켓 상세 | 로그인 | 200 |
| POST | `/api/admin/tickets/entry` | 입장 처리 | ADMIN | 200 |

## 내 티켓 목록

```http
GET /api/tickets/me
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchTicketListResponse](#schema-searchticketlistresponse).
- 요청 본문: 없음.
- Query: [SearchTicketCondition](#schema-searchticketcondition).
- Pagination: `page`, `size`, 반복 가능한 `sort` 사용. [공통 pagination](common.md#pagination) 참조.

name 부분 일치, type/status 일치 검색. 본인 티켓만 반환한다. 기본 sort=status,desc, id,desc. 허용 정렬: id, validDate, status, code, name, type, enteredAt, endTime, orderItem, createdAt, updatedAt. pgId는 부분 환불 호출에 사용하는 PG 식별자다.

## 내 티켓 상세

```http
GET /api/tickets/{ticketId}
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchTicketDetailResponse](#schema-searchticketdetailresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본인 소유 기준으로 조회한다. 없거나 다른 회원 소유이면 404 TICKET_NOT_FOUND.

## 입장 처리

```http
POST /api/admin/tickets/entry
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [CreateTicketEntryResponse](#schema-createticketentryresponse).
- 요청 본문: [CreateTicketEntryRequest](#schema-createticketentryrequest) (`application/json`).

UNUSED만 IN_USE로 변경한다. 방문일 당일, ALL_DAY는 10:00 이상, AFTERNOON은 15:00 이상이며 22:00 정각부터 거부한다. 중복 스캔은 성공을 반복하지 않고 상태 오류로 거절한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "ticketCode": "example-ticket-code"
}
```

## 자동 종료와 티켓 상태

방문일 22:00 기준으로 UNUSED → EXPIRED, IN_USE → USED 처리한다. 스케줄러 작업이며 별도의 수동 종료 HTTP API는 없다. REFUNDED는 환불 요청 단계에서 사용을 막는 상태로도 쓰이므로 금전 환불 완료 여부는 환불 상태로 판단한다.

## 대표 성공 응답 예시

다음은 `GET /api/tickets/me`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "ticketId": 1,
        "ticketCode": "example-ticket-code",
        "name": "종일권",
        "type": "ALL_DAY",
        "validDate": "2026-11-13",
        "status": "IN_USE",
        "orderId": 1,
        "pgId": "example-payment-id"
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

<a id="schema-searchticketlistresponse"></a>

### SearchTicketListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<SearchTicketResponse>` | — | 현재 페이지 항목 목록 |
| `number` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |

근거: [SearchTicketListResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/SearchTicketListResponse.java).

<a id="schema-searchticketresponse"></a>

### SearchTicketResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `ticketCode` | `String` | — | 입장권 코드 |
| `name` | `String` | — | 이름 |
| `type` | `TicketType` | — | 유형 |
| `validDate` | `LocalDate` | — | 입장권 방문일 |
| `status` | `TicketStatus` | — | 상태 |
| `orderId` | `Long` | — | 주문 ID |
| `pgId` | `String` | — | PG 결제 식별자(문자열) |

근거: [SearchTicketResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/SearchTicketResponse.java).

<a id="schema-searchticketcondition"></a>

### SearchTicketCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 선택 | 이름 |
| `type` | `TicketType` | 선택 | 유형 |
| `status` | `TicketStatus` | 선택 | 상태 |

근거: [SearchTicketCondition.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/SearchTicketCondition.java).

<a id="schema-searchticketdetailresponse"></a>

### SearchTicketDetailResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `ticketCode` | `String` | — | 입장권 코드 |
| `ticketProductId` | `Long` | — | 티켓 상품 ID |
| `name` | `String` | — | 이름 |
| `type` | `TicketType` | — | 유형 |
| `validDate` | `LocalDate` | — | 입장권 방문일 |
| `status` | `TicketStatus` | — | 상태 |
| `enteredAt` | `Instant` | — | 입장 처리 시각 |

근거: [SearchTicketDetailResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/SearchTicketDetailResponse.java).

<a id="schema-createticketentryrequest"></a>

### CreateTicketEntryRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketCode` | `String` | 필수; 빈 문자열·공백 불가 | 입장권 코드 |

근거: [CreateTicketEntryRequest.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/CreateTicketEntryRequest.java).

<a id="schema-createticketentryresponse"></a>

### CreateTicketEntryResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `ticketCode` | `String` | — | 입장권 코드 |
| `type` | `TicketType` | — | 유형 |
| `validDate` | `LocalDate` | — | 입장권 방문일 |
| `status` | `TicketStatus` | — | 상태 |
| `enteredAt` | `Instant` | — | 입장 처리 시각 |

근거: [CreateTicketEntryResponse.java](../../src/main/java/com/example/attractionreservation/domain/ticket/dto/CreateTicketEntryResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `TicketType` | `ALL_DAY`, `AFTERNOON` |
| `TicketStatus` | `UNUSED`, `IN_USE`, `USED`, `REFUNDED`, `EXPIRED` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 404 | `TICKET_PRODUCT_NOT_FOUND` | 티켓 상품을 찾을 수 없습니다. |
| 409 | `TICKET_PRODUCT_ALREADY_DELETED` | 삭제된 티켓 상품은 수정할 수 없습니다. |
| 409 | `TICKET_PRODUCT_STOCK_INSUFFICIENT` | 티켓 상품 재고가 부족합니다. |
| 400 | `INVALID_TICKET_SORT` | 지원하지 않는 티켓 정렬 기준입니다. |
| 404 | `TICKET_NOT_FOUND` | 티켓을 찾을 수 없습니다. |
| 409 | `TICKET_ENTRY_NOT_ALLOWED_STATUS` | 입장 가능한 티켓 상태가 아닙니다. |
| 400 | `TICKET_ENTRY_DATE_MISMATCH` | 티켓의 방문일이 아닙니다. |
| 400 | `TICKET_ENTRY_TIME_NOT_ALLOWED` | 해당 티켓의 입장 가능 시간이 아닙니다. |
| 403 | `TICKET_ACCESS_DENIED` | 본인의 티켓만 사용할 수 있습니다. |
| 409 | `TICKET_RESERVATION_NOT_ALLOWED_STATUS` | 예약에 사용할 수 없는 티켓 상태입니다. |
| 400 | `TICKET_RESERVATION_DATE_MISMATCH` | 티켓 방문일과 슬롯 날짜가 일치하지 않습니다. |
| 400 | `TICKET_RESERVATION_TIME_NOT_ALLOWED` | 티켓 이용 가능 시간에 포함되지 않는 슬롯입니다. |
| 400 | `INVALID_TICKET_ENTRY_TIME` | 입장 처리 시각은 필수입니다. |
| 409 | `INVALID_TICKET_STATUS_TRANSITION` | 유효하지 않은 상태 변경입니다. |
| 409 | `TICKET_NOT_REFUNDABLE` | 미사용 티켓만 환불할 수 있습니다. |

## 구현 근거

[AdminTicketController.java](../../src/main/java/com/example/attractionreservation/domain/ticket/controller/AdminTicketController.java), [TicketController.java](../../src/main/java/com/example/attractionreservation/domain/ticket/controller/TicketController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
