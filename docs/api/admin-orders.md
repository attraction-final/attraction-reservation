# 관리자 주문 API 및 주문 상세 결제번호

## 공통 사항

관리자 주문 API는 `ADMIN` 권한이 필요하다.

```http
Authorization: Bearer {accessToken}
```

미인증 요청은 HTTP 401, 일반 회원의 관리자 API 접근은 HTTP 403으로 거절한다.

성공 응답은 다음 구조를 사용한다.

```json
{
  "success": true,
  "message": "처리 결과 메시지",
  "data": {}
}
```

생성 시각은 KST 기준이며, 페이지 번호는 0부터 시작한다.

## 관리자 주문 목록

```http
GET /api/admin/orders
```

| 쿼리 | 기본값 | 조건 |
|---|---|---|
| `page` | 0 | 0 이상 |
| `size` | 10 | 1~100 |
| `userId` | 없음 | 양수, 지정한 회원의 주문만 조회 |
| `status` | 없음 | 지정한 주문 상태만 조회 |

상태값:

- `PENDING`: 결제 대기
- `COMPLETED`: 결제 완료
- `PARTIALLY_REFUNDED`: 부분 환불
- `REFUNDED`: 전체 환불
- `CANCELLED`: 취소

회원과 상태 조건은 함께 적용할 수 있다. 생성 시각 내림차순, 같은 시각이면 주문 ID 내림차순으로 정렬한다.

요청 예시:

```http
GET /api/admin/orders?page=0&size=10&userId=7&status=PENDING
```

응답 예시:

```json
{
  "success": true,
  "message": "관리자 주문 목록 조회에 성공했습니다.",
  "data": {
    "content": [
      {
        "orderId": 101,
        "userId": 7,
        "email": "member@example.test",
        "nickname": "주문회원",
        "totalAmount": 10000,
        "status": "PENDING",
        "visitDate": "2026-11-05",
        "createdAt": "2026-11-04T10:00:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

조회 결과가 없으면 `content`는 빈 배열이다.

## 관리자 주문 상세

```http
GET /api/admin/orders/{orderId}
```

응답 예시:

```json
{
  "success": true,
  "message": "관리자 주문 상세 조회에 성공했습니다.",
  "data": {
    "orderId": 101,
    "userId": 7,
    "email": "member@example.test",
    "nickname": "주문회원",
    "totalAmount": 10000,
    "status": "PENDING",
    "visitDate": "2026-11-05",
    "createdAt": "2026-11-04T10:00:00",
    "items": [
      {
        "id": 201,
        "ticketProductId": 301,
        "price": 10000,
        "quantity": 1
      }
    ],
    "pgId": "example-payment-id"
  }
}
```

`items[].id`는 주문 항목 ID이며 입장권 ID가 아니다.

`price`는 주문 당시 단가다. 결제 데이터가 없는 기존 주문은 `pgId`가 null이거나 응답 설정에 따라 생략될 수 있다.

## 관리자 대리 주문 생성

```http
POST /api/admin/orders
Content-Type: application/json
```

요청 예시:

```json
{
  "userId": 7,
  "items": [
    {
      "ticketProductId": 301,
      "quantity": 1
    }
  ],
  "visitDate": "2026-11-05"
}
```

- `userId`는 주문 대상 회원 ID다.
- 주문 대상 회원은 ACTIVE 상태여야 한다.
- 항목은 한 개 이상이며 상품 ID와 수량은 양수여야 한다.
- 방문일은 서비스 기준 오늘부터 29일 후까지 선택할 수 있다.
- 예시 날짜는 실제 요청 시 유효한 방문일로 변경한다.
- 가격과 총액은 서버에서 계산한다.
- 주문 소유자는 관리자 대신 요청의 대상 회원으로 저장한다.

성공 시 HTTP 201을 반환한다.

```json
{
  "success": true,
  "message": "관리자 주문 생성에 성공했습니다.",
  "data": {
    "orderId": 101,
    "totalAmount": 10000,
    "status": "PENDING",
    "pgId": "example-payment-id",
    "items": [
      {
        "id": 201,
        "ticketProductId": 301,
        "price": 10000,
        "quantity": 1
      }
    ]
  }
}
```

이 API는 결제 대기 주문과 결제 데이터를 생성한다. 결제 승인이나 입장권 발급까지 완료하는 API가 아니다.

## 관리자 주문 취소

```http
POST /api/admin/orders/{orderId}/cancel
```

요청 본문은 없다.

성공 시 HTTP 200을 반환한다.

```json
{
  "success": true,
  "message": "관리자 주문 취소에 성공했습니다.",
  "data": {
    "orderId": 101,
    "status": "CANCELLED"
  }
}
```

- PENDING 주문만 취소할 수 있다.
- 주문·결제를 취소하고 상품 재고를 복구한다.
- 같은 주문의 재취소는 거절한다.
- 결제 완료 주문은 이 API로 취소하지 않고 환불 흐름으로 처리한다.

## 기존 회원용 주문 상세 변경

```http
GET /api/orders/{orderId}
```

기존 응답의 `data`에 `pgId`를 추가했다.

- 본인 주문만 조회할 수 있다.
- 다른 회원의 주문은 기존과 동일하게 접근을 거절한다.
- 기존 응답 항목은 유지한다.
- 결제가 없는 기존 주문은 `pgId`가 null이거나 생략될 수 있다.
- 회원 주문 목록 `/api/orders/me` 응답에는 이번 작업으로 `pgId`를 추가하지 않았다.

입장권 화면의 환불 연결에는 입장권 응답의 `orderId`도 필요하다. 해당 필드 추가는 별도 작업이다.

## 주요 오류

| HTTP 상태 | 상황 |
|---|---|
| 400 | 잘못된 페이지·회원 ID·수량·상태값·방문일 |
| 401 | 미인증 또는 유효하지 않은 인증 |
| 403 | 관리자 권한 없음 또는 주문 대상 회원이 ACTIVE가 아님 |
| 404 | 대상 회원·주문·상품·필요한 결제 데이터 없음 |
| 409 | 재고 부족, 판매 불가 상품, 취소할 수 없는 주문 상태 |

업무 오류 응답 예시:

```json
{
  "success": false,
  "message": "결제 대기 상태의 주문만 취소할 수 있습니다."
}
```

현재 공통 응답에는 별도의 `errorCode` 필드가 없다. 입력 검증 오류에는 `data`에 필드별 오류 정보가 포함될 수 있다.

## 검증 및 연동 상태

관리자 권한, 입력 검증, DB 필터·정렬, 대상 회원 소유권, 재고·결제 상태, 재취소 방지를 테스트했다. 기존 주문·취소·동시성·보안 관련 검사와 함께 140개 테스트가 통과했다.

관리자 생성·취소 완료 로그는 커밋 이후 관리자 ID·대상 회원 ID·주문 ID를 포함해 기록한다.

프론트 연동과 배포 환경 검증은 별도로 진행한다. 관리자 환불 API와 입장권 `orderId` 추가는 이 작업에 포함하지 않는다.