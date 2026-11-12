# 예약 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

본인 예약과 관리자 예약 조회·취소·완료·노쇼를 관리한다. 시간 검증은 서버 KST 기준이다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| POST | `/api/reservations` | 예약 생성 | 로그인 | 201 |
| GET | `/api/reservations/me` | 내 예약 목록 | 로그인 | 200 |
| GET | `/api/reservations/{reservationId}` | 내 예약 상세 | 로그인 | 200 |
| PATCH | `/api/reservations/{reservationId}/cancel` | 내 예약 취소 | 로그인 | 200 |
| GET | `/api/admin/reservations` | 전체 예약 목록 | ADMIN | 200 |
| GET | `/api/admin/reservations/{reservationId}` | 예약 상세 | ADMIN | 200 |
| PATCH | `/api/admin/reservations/{reservationId}/cancel` | 예약 강제 취소 | ADMIN | 200 |
| PATCH | `/api/admin/reservations/{reservationId}/status` | 완료·노쇼 처리 | ADMIN | 200 |

## 예약 생성

```http
POST /api/reservations
```

- 접근: **로그인**.
- 성공: **201**. `data`: [CreateReservationResponse](#schema-createreservationresponse).
- 요청 본문: [CreateReservationRequest](#schema-createreservationrequest) (`application/json`).

본인 소유 IN_USE 티켓, 방문일=오늘=슬롯 날짜, 티켓 이용 시간, OPERATING 어트랙션, OPEN 슬롯 및 잔여 정원을 확인한다. 잠금 확보 후 기준 시각으로 슬롯 시작 30분 전까지 허용하며 정각은 허용한다. 동일 회원의 활성 중복 슬롯·겹치는 탑승 시간은 409. 한 건은 한 자리다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "ticketId": 1,
  "slotId": 1
}
```

## 내 예약 목록

```http
GET /api/reservations/me
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchReservationListResponse](#schema-searchreservationlistresponse).
- 요청 본문: 없음.
- Query: [SearchReservationCondition](#schema-searchreservationcondition).
- Pagination: `page`, `size`, 반복 가능한 `sort` 사용. [공통 pagination](common.md#pagination) 참조.

date는 슬롯 시작 날짜 조건이며 [당일 00:00, 다음날 00:00)로 검색한다. 기본 sort=status,desc, id,desc.

## 내 예약 상세

```http
GET /api/reservations/{reservationId}
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchReservationDetailResponse](#schema-searchreservationdetailresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

없거나 타인 예약이면 404 NOT_FOUND_RESERVATION.

## 내 예약 취소

```http
PATCH /api/reservations/{reservationId}/cancel
```

- 접근: **로그인**.
- 성공: **200**. `data`: [UpdateReservationCancelResponse](#schema-updatereservationcancelresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

본문 없음. 본인 RESERVED 예약만, 슬롯 시작 30분 전까지 허용(정각 허용). 상태와 취소 시각을 기록하고 슬롯 정원을 한 번 복구한다. 금전 환불은 없다.

## 전체 예약 목록

```http
GET /api/admin/reservations
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAdminReservationListResponse](#schema-searchadminreservationlistresponse).
- 요청 본문: 없음.
- Query: [SearchAdminReservationCondition](#schema-searchadminreservationcondition).
- Pagination: `page`, `size`, 반복 가능한 `sort` 사용. [공통 pagination](common.md#pagination) 참조.

userId·attractionId·status·date 조건을 함께 적용한다. 기본 sort=status,desc, id,desc.

## 예약 상세

```http
GET /api/admin/reservations/{reservationId}
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAdminReservationDetailResponse](#schema-searchadminreservationdetailresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

## 예약 강제 취소

```http
PATCH /api/admin/reservations/{reservationId}/cancel
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAdminReservationCancelResponse](#schema-updateadminreservationcancelresponse).
- 요청 본문: [UpdateAdminReservationCancelRequest](#schema-updateadminreservationcancelrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

RESERVED만 취소한다. 사용자 취소 마감 시간은 적용하지 않는다. cancelReason은 enum 이름으로 전달한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "cancelReason": "OPERATIONAL_ISSUE"
}
```

## 완료·노쇼 처리

```http
PATCH /api/admin/reservations/{reservationId}/status
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAdminReservationStatusResponse](#schema-updateadminreservationstatusresponse).
- 요청 본문: [UpdateAdminReservationStatusRequest](#schema-updateadminreservationstatusrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

RESERVED에서 COMPLETED 또는 NO_SHOW만 허용한다. 슬롯 종료 정각 이상이어야 한다. 취소 API와 다르게 슬롯 예약 인원을 복구하지 않는다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "status": "COMPLETED"
}
```

## 정렬과 상태

목록의 허용 sort: `id`, `status`, `cancelReason`, `cancelledAt`, `reservationNumber`, `userId`/`user.id`, `ticketId`/`ticket.id`, `slotId`/`attractionSlot.id`, `createdAt`, `updatedAt`. `startsAt` 정렬이 지원된다고 가정하지 않는다.

HTTP API의 취소 상태 표기는 `CANCELLED`이다. 사용자 시나리오 원본에 남은 `CANCELED`와 구분한다. 관리자 변경 주체는 로그에 남지만 예약 DTO에 처리 관리자 ID를 제공하는 계약은 없다.

## 대표 성공 응답 예시

다음은 `POST /api/reservations`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "reservationId": 1,
    "reservationNumber": "예시",
    "ticketId": 1,
    "slotId": 1,
    "attractionName": "예시",
    "status": "RESERVED",
    "startsAt": "2026-11-13T14:00:00",
    "endsAt": "2026-11-13T14:10:00",
    "createdAt": "2026-11-13T01:00:00Z"
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-createreservationrequest"></a>

### CreateReservationRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `ticketId` | `Long` | 필수; 최소 1 | 개별 입장권 ID |
| `slotId` | `Long` | 필수; 최소 1 | 슬롯 ID |

근거: [CreateReservationRequest.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/CreateReservationRequest.java).

<a id="schema-createreservationresponse"></a>

### CreateReservationResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `slotId` | `Long` | — | 슬롯 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `status` | `ReservationStatus` | — | 상태 |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `createdAt` | `Instant` | — | 생성 시각 |

근거: [CreateReservationResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/CreateReservationResponse.java).

<a id="schema-searchreservationlistresponse"></a>

### SearchReservationListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<SearchReservationResponse>` | — | 현재 페이지 항목 목록 |
| `number` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |

근거: [SearchReservationListResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationListResponse.java).

<a id="schema-searchreservationresponse"></a>

### SearchReservationResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `slotId` | `Long` | — | 슬롯 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `status` | `ReservationStatus` | — | 상태 |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `createdAt` | `Instant` | — | 생성 시각 |

근거: [SearchReservationResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationResponse.java).

<a id="schema-searchreservationcondition"></a>

### SearchReservationCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `status` | `ReservationStatus` | 선택 | 상태 |
| `date` | `LocalDate` | 선택 | 슬롯 시작 날짜 검색 조건 |

근거: [SearchReservationCondition.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationCondition.java).

<a id="schema-searchreservationdetailresponse"></a>

### SearchReservationDetailResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `status` | `ReservationStatus` | — | 상태 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `cancelReason` | `ReservationCancelReason` | — | 취소 사유 enum |
| `cancelReasonMessage` | `String` | — | 취소 사유 설명 |
| `cancelledAt` | `Instant` | — | 취소 시각 |

근거: [SearchReservationDetailResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchReservationDetailResponse.java).

<a id="schema-updatereservationcancelresponse"></a>

### UpdateReservationCancelResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `status` | `ReservationStatus` | — | 상태 |
| `cancelledAt` | `Instant` | — | 취소 시각 |

근거: [UpdateReservationCancelResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/UpdateReservationCancelResponse.java).

<a id="schema-searchadminreservationlistresponse"></a>

### SearchAdminReservationListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<SearchAdminReservationResponse>` | — | 현재 페이지 항목 목록 |
| `number` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |

근거: [SearchAdminReservationListResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationListResponse.java).

<a id="schema-searchadminreservationresponse"></a>

### SearchAdminReservationResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `userId` | `Long` | — | 대상 회원 ID |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `status` | `ReservationStatus` | — | 상태 |
| `createdAt` | `Instant` | — | 생성 시각 |

근거: [SearchAdminReservationResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationResponse.java).

<a id="schema-searchadminreservationcondition"></a>

### SearchAdminReservationCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `userId` | `Long` | 선택; 최소 1 | 대상 회원 ID |
| `attractionId` | `Long` | 선택; 최소 1 | 어트랙션 ID |
| `status` | `ReservationStatus` | 선택 | 상태 |
| `date` | `LocalDate` | 선택 | 슬롯 시작 날짜 검색 조건 |

근거: [SearchAdminReservationCondition.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationCondition.java).

<a id="schema-searchadminreservationdetailresponse"></a>

### SearchAdminReservationDetailResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `userId` | `Long` | — | 대상 회원 ID |
| `ticketId` | `Long` | — | 개별 입장권 ID |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `status` | `ReservationStatus` | — | 상태 |
| `createdAt` | `Instant` | — | 생성 시각 |
| `cancelledAt` | `Instant` | — | 취소 시각 |
| `cancelReason` | `ReservationCancelReason` | — | 취소 사유 enum |
| `cancelReasonMessage` | `String` | — | 취소 사유 설명 |

근거: [SearchAdminReservationDetailResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/SearchAdminReservationDetailResponse.java).

<a id="schema-updateadminreservationcancelrequest"></a>

### UpdateAdminReservationCancelRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `cancelReason` | `ReservationCancelReason` | 필수 | 취소 사유 enum |

근거: [UpdateAdminReservationCancelRequest.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/UpdateAdminReservationCancelRequest.java).

<a id="schema-updateadminreservationcancelresponse"></a>

### UpdateAdminReservationCancelResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `status` | `ReservationStatus` | — | 상태 |
| `cancelledAt` | `Instant` | — | 취소 시각 |
| `cancelReason` | `ReservationCancelReason` | — | 취소 사유 enum |
| `cancelReasonMessage` | `String` | — | 취소 사유 설명 |

근거: [UpdateAdminReservationCancelResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/UpdateAdminReservationCancelResponse.java).

<a id="schema-updateadminreservationstatusrequest"></a>

### UpdateAdminReservationStatusRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `status` | `ReservationStatus` | 필수 | 상태 |

근거: [UpdateAdminReservationStatusRequest.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/UpdateAdminReservationStatusRequest.java).

<a id="schema-updateadminreservationstatusresponse"></a>

### UpdateAdminReservationStatusResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `reservationId` | `Long` | — | 예약 ID |
| `reservationNumber` | `String` | — | 예약 번호 |
| `status` | `ReservationStatus` | — | 상태 |
| `updatedAt` | `Instant` | — | 수정 시각 |

근거: [UpdateAdminReservationStatusResponse.java](../../src/main/java/com/example/attractionreservation/domain/reservation/dto/UpdateAdminReservationStatusResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `ReservationStatus` | `RESERVED`, `CANCELLED`, `COMPLETED`, `NO_SHOW` |
| `ReservationCancelReason` | `ATTRACTION_MAINTENANCE`, `WEATHER_CONDITION`, `SAFETY_CONCERN`, `OPERATIONAL_ISSUE`, `USER_REQUEST`, `OTHER`, `USER_WITHDRAWAL` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 409 | `ATTRACTION_NOT_OPERATING` | 현재 운영 중인 어트랙션이 아닙니다. |
| 404 | `SLOT_NOT_FOUND` | 슬롯을 찾을 수 없습니다. |
| 409 | `SLOT_CAPACITY_BELOW_RESERVED` | 예약된 인원보다 적게 정원을 줄일 수 없습니다. |
| 409 | `SLOT_RESERVATION_NOT_ALLOWED_STATUS` | 예약 가능한 슬롯 상태가 아닙니다. |
| 409 | `SLOT_CAPACITY_ACQUISITION_FAILED` | 슬롯이 마감되었거나 잔여 정원이 없어 예약할 수 없습니다. |
| 403 | `USER_NOT_ACTIVE` | 이용할 수 없는 회원 상태입니다. |
| 400 | `TICKET_ENTRY_DATE_MISMATCH` | 티켓의 방문일이 아닙니다. |
| 403 | `TICKET_ACCESS_DENIED` | 본인의 티켓만 사용할 수 있습니다. |
| 409 | `TICKET_RESERVATION_NOT_ALLOWED_STATUS` | 예약에 사용할 수 없는 티켓 상태입니다. |
| 400 | `TICKET_RESERVATION_DATE_MISMATCH` | 티켓 방문일과 슬롯 날짜가 일치하지 않습니다. |
| 400 | `TICKET_RESERVATION_TIME_NOT_ALLOWED` | 티켓 이용 가능 시간에 포함되지 않는 슬롯입니다. |
| 400 | `INVALID_RESERVATION_USER` | 예약 회원 정보가 필요합니다. |
| 400 | `INVALID_RESERVATION_TICKET` | 예약에 사용할 티켓 정보가 필요합니다. |
| 400 | `INVALID_RESERVATION_SLOT` | 예약할 슬롯 정보가 필요합니다. |
| 409 | `RESERVATION_DEADLINE_PASSED` | 슬롯 시작 30분 전까지만 예약할 수 있습니다. |
| 409 | `RESERVATION_DUPLICATED` | 이미 예약한 슬롯입니다. |
| 409 | `RESERVATION_TIME_CONFLICT` | 다른 예약과 탑승 시간이 겹칩니다. |
| 404 | `NOT_FOUND_RESERVATION` | 예약을 찾을 수 없습니다. |
| 409 | `RESERVATION_CANCEL_NOT_ALLOWED_STATUS` | 예약 완료 상태에서만 취소할 수 있습니다. |
| 409 | `RESERVATION_CANCEL_DEADLINE_PASSED` | 슬롯 시작 30분 전까지만 예약을 취소할 수 있습니다. |
| 409 | `RESERVATION_CAPACITY_RESTORE_FAILED` | 슬롯 예약 인원을 복구할 수 없어 예약 취소에 실패했습니다. |
| 400 | `INVALID_RESERVATION_CANCEL_REASON` | 예약 취소 사유는 필수입니다. |
| 409 | `RESERVATION_STATUS_CHANGE_NOT_ALLOWED` | 예약 완료 상태에서만 이용 완료 또는 노쇼로 변경할 수 있습니다. |
| 400 | `INVALID_RESERVATION_TARGET_STATUS` | 변경할 예약 상태는 이용 완료 또는 노쇼만 가능합니다. |
| 409 | `RESERVATION_SLOT_NOT_ENDED` | 슬롯 종료 후에만 이용 완료 또는 노쇼로 변경할 수 있습니다. |

## 구현 근거

[AdminReservationController.java](../../src/main/java/com/example/attractionreservation/domain/reservation/controller/AdminReservationController.java), [ReservationController.java](../../src/main/java/com/example/attractionreservation/domain/reservation/controller/ReservationController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
