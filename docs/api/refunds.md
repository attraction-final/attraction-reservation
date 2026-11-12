# 환불 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

입장권 단위의 전체·부분 환불 요청 API다. HTTP 200은 환불 요청 처리 응답이며 금전 환불 완료는 data.status로 판단한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| POST | `/api/payments/{pgId}/refunds` | 내 결제 전체 환불 | 로그인 | 200 |
| POST | `/api/payments/{pgId}/refunds/partial` | 내 결제 부분 환불 | 로그인 | 200 |
| POST | `/api/admin/payments/{pgId}/refunds` | 관리자 전체 강제 환불 | ADMIN | 200 |
| POST | `/api/admin/payments/{pgId}/refunds/partial` | 관리자 부분 강제 환불 | ADMIN | 200 |

## 내 결제 전체 환불

```http
POST /api/payments/{pgId}/refunds
```

- 접근: **로그인**.
- 성공: **200**. `data`: [RefundResponse](#schema-refundresponse).
- 요청 본문: [CreateRefundRequest](#schema-createrefundrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본인 결제에서 현재 환불 가능한 UNUSED 티켓 전체를 대상으로 한다. 남은 미사용 티켓이 없으면 409. KST 방문일 전날까지 요청 가능하다. 타인 결제는 404.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "reason": "일정 변경"
}
```

## 내 결제 부분 환불

```http
POST /api/payments/{pgId}/refunds/partial
```

- 접근: **로그인**.
- 성공: **200**. `data`: [RefundResponse](#schema-refundresponse).
- 요청 본문: [CreatePartialRefundRequest](#schema-createpartialrefundrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

ticketIds는 개별 티켓 ID 목록이며 주문 항목 ID가 아니다. null·빈 목록·중복·해당 주문 외 티켓을 거부한다. 사용자 전체 환불과 같은 기한·소유권·상태 검증을 적용한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "ticketIds": [
    1
  ],
  "reason": "일정 변경"
}
```

## 관리자 전체 강제 환불

```http
POST /api/admin/payments/{pgId}/refunds
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [RefundResponse](#schema-refundresponse).
- 요청 본문: [CreateRefundRequest](#schema-createrefundrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본인 소유권 제한과 사용자 환불 기한을 적용하지 않는다. UNUSED 티켓만 대상이며 사용중·사용완료·만료 티켓은 강제 환불 대상이 아니다. 탈퇴 회원 결제도 거부한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "reason": "일정 변경"
}
```

## 관리자 부분 강제 환불

```http
POST /api/admin/payments/{pgId}/refunds/partial
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [RefundResponse](#schema-refundresponse).
- 요청 본문: [CreatePartialRefundRequest](#schema-createpartialrefundrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

기한·소유권 제한 해제 외 티켓·결제·중복 검증은 유지한다. 탈퇴 회원 결제는 환불 불가.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "ticketIds": [
    1
  ],
  "reason": "일정 변경"
}
```

## 요청 이후 상태 해석

환불 요청 트랜잭션에서 대상 티켓은 REFUNDED로 바뀌어 입장·재환불을 막는다. 이후 PG 취소를 즉시 한 번 시도하고, 응답에는 시도 후 조회한 상태를 담는다. 재시도·PG 대기 상태가 남을 수 있다.

| 환불 상태 | 의미 |
|---|---|
| REQUESTED | 요청 저장, 처리 대기 |
| PROCESSING | PG 취소 처리 중 |
| PG_PENDING | PG 취소 결과 대기 |
| COMPLETED | 환불 완료 |
| FAILED | 실패 후 재시도 대상 |
| DEAD | 자동 처리 종료, 확인 필요 |

티켓의 REFUNDED만 보고 금전 환불 완료로 표시하지 않는다. 결제·주문 상태와 재고 복구는 환불 완료 처리에서 반영한다. 완료되지 않은 환불은 회원 탈퇴를 막는다. 현재 Controller에는 별도 환불 상태 조회·재시도 endpoint가 없다.

```json
{"success":true,"message":"부분 환불 요청에 성공했습니다.","data":{"refundId":1,"refundAmount":35000,"status":"PG_PENDING"}}
```

이 예시는 응답 형태를 설명하며 매번 PG_PENDING으로 응답한다는 뜻이 아니다.

## 대표 성공 응답 예시

다음은 `POST /api/payments/{pgId}/refunds`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "refundId": 1,
    "refundAmount": 1,
    "status": "REQUESTED"
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-createrefundrequest"></a>

### CreateRefundRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reason` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 환불 사유 |

근거: [CreateRefundRequest.java](../../src/main/java/com/example/attractionreservation/domain/refund/dto/CreateRefundRequest.java).

<a id="schema-refundresponse"></a>

### RefundResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `refundId` | `Long` | — | 환불 ID |
| `refundAmount` | `int` | — | 환불 요청 금액(원) |
| `status` | `RefundStatus` | — | 상태 |

근거: [RefundResponse.java](../../src/main/java/com/example/attractionreservation/domain/refund/dto/RefundResponse.java).

<a id="schema-createpartialrefundrequest"></a>

### CreatePartialRefundRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketIds` | `List< Long>` | 필수; 빈 목록 불가 | 환불할 개별 티켓 ID 목록 |
| `reason` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 환불 사유 |

근거: [CreatePartialRefundRequest.java](../../src/main/java/com/example/attractionreservation/domain/refund/dto/CreatePartialRefundRequest.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `RefundStatus` | `REQUESTED`, `PROCESSING`, `PG_PENDING`, `COMPLETED`, `FAILED`, `DEAD` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 409 | `TICKET_NOT_REFUNDABLE` | 미사용 티켓만 환불할 수 있습니다. |
| 404 | `PAYMENT_NOT_FOUND` | 결제 내역을 찾을 수 없습니다. |
| 400 | `REFUND_INVALID_QUANTITY` | 환불 수량이 올바르지 않습니다. |
| 409 | `REFUND_NOT_REFUNDABLE_PAYMENT` | 환불할 수 없는 결제 상태입니다. |
| 400 | `REFUND_INVALID_TICKET` | 해당 주문의 티켓이 아닙니다. |
| 400 | `REFUND_DUPLICATE_TICKET` | 같은 티켓을 중복해서 환불할 수 없습니다. |
| 409 | `REFUND_DEADLINE_PASSED` | 입장일 전날까지만 환불할 수 있습니다. |
| 409 | `REFUND_USER_WITHDRAW` | 탈퇴한 회원의 결제는 환불을 요청할 수 없습니다. |

## 구현 근거

[AdminRefundController.java](../../src/main/java/com/example/attractionreservation/domain/refund/controller/AdminRefundController.java), [RefundController.java](../../src/main/java/com/example/attractionreservation/domain/refund/controller/RefundController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
