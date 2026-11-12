# 어트랙션 슬롯 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

어트랙션별 날짜 슬롯 조회와 관리자 슬롯 생성·시간·정원·상태 변경 API다. 사용자 조회도 로그인해야 한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/attractions/{attractionId}/slots` | 날짜별 슬롯 조회 | 로그인 | 200 |
| POST | `/api/admin/attractions/{attractionId}/slots` | 슬롯 등록 | ADMIN | 201 |
| GET | `/api/admin/attractions/{attractionId}/slots` | 관리자 날짜별 슬롯 조회 | ADMIN | 200 |
| PATCH | `/api/admin/slots/{slotId}/time` | 슬롯 시간 변경 | ADMIN | 200 |
| PATCH | `/api/admin/slots/{slotId}/capacity` | 슬롯 정원 변경 | ADMIN | 200 |
| PATCH | `/api/admin/slots/{slotId}/status` | 슬롯 상태 변경 | ADMIN | 200 |
| DELETE | `/api/admin/slots/{slotId}` | 슬롯 삭제 | ADMIN | 200 |

## 날짜별 슬롯 조회

```http
GET /api/attractions/{attractionId}/slots
```

- 접근: **로그인**.
- 성공: **200**. `data`: [SearchAttractionSlotResponse](#schema-searchattractionslotresponse).
- 요청 본문: 없음.
- Query: date: LocalDate, 선택, yyyy-MM-dd.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

date 생략 시 서버 KST 오늘. CANCELLED 슬롯은 제외하지만 마감·중단 슬롯도 응답하고 reservable/unavailableReason으로 구분한다.

## 슬롯 등록

```http
POST /api/admin/attractions/{attractionId}/slots
```

- 접근: **ADMIN**.
- 성공: **201**. `data`: [CreateAttractionSlotResponse](#schema-createattractionslotresponse).
- 요청 본문: [CreateAttractionSlotRequest](#schema-createattractionslotrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

종료는 시작+어트랙션 소요 시간으로 서버 계산한다. 시작은 현재보다 미래, 10:00~22:00 운영 시간 안이고 같은 어트랙션 슬롯과 겹치지 않아야 한다. 22:00 종료 허용. 초기 OPEN, reservedCount=0.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "startsAt": "2026-11-13T14:00:00",
  "capacity": 20
}
```

## 관리자 날짜별 슬롯 조회

```http
GET /api/admin/attractions/{attractionId}/slots
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAdminAttractionSlotResponse](#schema-searchadminattractionslotresponse).
- 요청 본문: 없음.
- Query: date: LocalDate, 필수, yyyy-MM-dd; includeCancelled: boolean, 선택, 기본 false.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

includeCancelled=true일 때 취소 슬롯도 반환한다.

## 슬롯 시간 변경

```http
PATCH /api/admin/slots/{slotId}/time
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAttractionSlotTimeResponse](#schema-updateattractionslottimeresponse).
- 요청 본문: [UpdateAttractionSlotTimeRequest](#schema-updateattractionslottimerequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

예약 인원이 있으면 거절한다. 종료는 서버가 다시 계산하고 미래·운영 시간·중복을 검사한다. 종료·취소된 슬롯은 변경 불가.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "startsAt": "2026-11-13T14:00:00"
}
```

## 슬롯 정원 변경

```http
PATCH /api/admin/slots/{slotId}/capacity
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAttractionSlotCapacityResponse](#schema-updateattractionslotcapacityresponse).
- 요청 본문: [UpdateAttractionSlotCapacityRequest](#schema-updateattractionslotcapacityrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

현재 reservedCount보다 작게 설정할 수 없다. 정원 부족으로 자동 마감된 슬롯만 잔여 정원이 생길 때 다시 연다. 수동 CLOSED/SUSPENDED는 유지한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "capacity": 20
}
```

## 슬롯 상태 변경

```http
PATCH /api/admin/slots/{slotId}/status
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAttractionSlotStatusResponse](#schema-updateattractionslotstatusresponse).
- 요청 본문: [UpdateAttractionSlotStatusRequest](#schema-updateattractionslotstatusrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

OPEN, CLOSED, SUSPENDED만 가능. 잔여 정원 없이 OPEN으로 바꾸면 409. 기존 예약을 자동 취소하는 API가 아니다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "status": "OPEN"
}
```

## 슬롯 삭제

```http
DELETE /api/admin/slots/{slotId}
```

- 접근: **ADMIN**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

예약 인원이 있거나 이미 종료·취소된 슬롯은 거부한다. 실제 행 삭제 대신 CANCELLED로 변경한다.

## 예약 불가 사유

`ATTRACTION_NOT_OPERATING`: 어트랙션 운영 안 함, `SUSPENDED`: 슬롯 운영 중단, `DEADLINE_PASSED`: 시작 30분 전 마감 경과, `MANUALLY_CLOSED`: 관리자 수동 마감, `FULL`: 잔여 정원 없음. 예약 가능하면 `unavailableReason`은 null이다. 수동 마감을 매진으로 안내하지 않는다.

## 대표 성공 응답 예시

다음은 `GET /api/attractions/{attractionId}/slots`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "attractionId": 1,
    "attractionName": "예시",
    "operationStatus": "OPERATING",
    "date": "2026-11-13",
    "slots": [
      {
        "slotId": 1,
        "startsAt": "2026-11-13T14:00:00",
        "endsAt": "2026-11-13T14:10:00",
        "capacity": 20,
        "remainingCount": 19,
        "status": "OPEN",
        "reservable": true,
        "unavailableReason": "ATTRACTION_NOT_OPERATING"
      }
    ]
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-searchattractionslotresponse"></a>

### SearchAttractionSlotResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `operationStatus` | `OperationStatus` | — | 어트랙션 운영 상태 |
| `date` | `LocalDate` | — | 슬롯 시작 날짜 검색 조건 |
| `slots` | `List<SlotItem>` | — | 해당 날짜 슬롯 목록 |

근거: [SearchAttractionSlotResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/SearchAttractionSlotResponse.java).

<a id="schema-searchattractionslotresponse-slotitem"></a>

### SearchAttractionSlotResponse.SlotItem

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `capacity` | `Integer` | — | 슬롯 정원 |
| `remainingCount` | `Integer` | — | 잔여 정원 |
| `status` | `SlotStatus` | — | 상태 |
| `reservable` | `Boolean` | — | 조회 시점 예약 가능 여부 또는 검색 조건 |
| `unavailableReason` | `UnavailableReason` | — | 예약 불가 사유 |

근거: [SearchAttractionSlotResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/SearchAttractionSlotResponse.java).

<a id="schema-createattractionslotrequest"></a>

### CreateAttractionSlotRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `startsAt` | `LocalDateTime` | 필수 | 슬롯 시작 KST |
| `capacity` | `Integer` | 필수; 최소 1 | 슬롯 정원 |

근거: [CreateAttractionSlotRequest.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/CreateAttractionSlotRequest.java).

<a id="schema-createattractionslotresponse"></a>

### CreateAttractionSlotResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `capacity` | `Integer` | — | 슬롯 정원 |
| `reservedCount` | `Integer` | — | 예약 인원 |
| `remainingCount` | `Integer` | — | 잔여 정원 |
| `status` | `SlotStatus` | — | 상태 |

근거: [CreateAttractionSlotResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/CreateAttractionSlotResponse.java).

<a id="schema-searchadminattractionslotresponse"></a>

### SearchAdminAttractionSlotResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `date` | `LocalDate` | — | 슬롯 시작 날짜 검색 조건 |
| `slots` | `List<SlotItem>` | — | 해당 날짜 슬롯 목록 |

근거: [SearchAdminAttractionSlotResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/SearchAdminAttractionSlotResponse.java).

<a id="schema-searchadminattractionslotresponse-slotitem"></a>

### SearchAdminAttractionSlotResponse.SlotItem

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `capacity` | `Integer` | — | 슬롯 정원 |
| `reservedCount` | `Integer` | — | 예약 인원 |
| `remainingCount` | `Integer` | — | 잔여 정원 |
| `status` | `SlotStatus` | — | 상태 |

근거: [SearchAdminAttractionSlotResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/SearchAdminAttractionSlotResponse.java).

<a id="schema-updateattractionslottimerequest"></a>

### UpdateAttractionSlotTimeRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `startsAt` | `LocalDateTime` | 필수 | 슬롯 시작 KST |

근거: [UpdateAttractionSlotTimeRequest.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotTimeRequest.java).

<a id="schema-updateattractionslottimeresponse"></a>

### UpdateAttractionSlotTimeResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `endsAt` | `LocalDateTime` | — | 슬롯 종료 KST |
| `capacity` | `Integer` | — | 슬롯 정원 |
| `reservedCount` | `Integer` | — | 예약 인원 |
| `remainingCount` | `Integer` | — | 잔여 정원 |
| `status` | `SlotStatus` | — | 상태 |

근거: [UpdateAttractionSlotTimeResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotTimeResponse.java).

<a id="schema-updateattractionslotcapacityrequest"></a>

### UpdateAttractionSlotCapacityRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `capacity` | `Integer` | 필수; 최소 1 | 슬롯 정원 |

근거: [UpdateAttractionSlotCapacityRequest.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotCapacityRequest.java).

<a id="schema-updateattractionslotcapacityresponse"></a>

### UpdateAttractionSlotCapacityResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `capacity` | `Integer` | — | 슬롯 정원 |
| `reservedCount` | `Integer` | — | 예약 인원 |
| `remainingCount` | `Integer` | — | 잔여 정원 |
| `status` | `SlotStatus` | — | 상태 |

근거: [UpdateAttractionSlotCapacityResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotCapacityResponse.java).

<a id="schema-updateattractionslotstatusrequest"></a>

### UpdateAttractionSlotStatusRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `status` | `String` | 필수; 빈 문자열·공백 불가 | 상태 |

근거: [UpdateAttractionSlotStatusRequest.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotStatusRequest.java).

<a id="schema-updateattractionslotstatusresponse"></a>

### UpdateAttractionSlotStatusResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `previousStatus` | `SlotStatus` | — | 변경 전 슬롯 상태 |
| `status` | `SlotStatus` | — | 상태 |
| `reservedCount` | `Integer` | — | 예약 인원 |

근거: [UpdateAttractionSlotStatusResponse.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/dto/UpdateAttractionSlotStatusResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `OperationStatus` | `OPERATING`, `MAINTENANCE`, `SUSPENDED`, `DELETED` |
| `SlotStatus` | `OPEN`, `CLOSED`, `SUSPENDED`, `CANCELLED` |
| `UnavailableReason` | `ATTRACTION_NOT_OPERATING`, `SUSPENDED`, `DEADLINE_PASSED`, `MANUALLY_CLOSED`, `FULL` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 404 | `ATTRACTION_NOT_FOUND` | 어트랙션을 찾을 수 없습니다. |
| 404 | `SLOT_NOT_FOUND` | 슬롯을 찾을 수 없습니다. |
| 400 | `SLOT_OUT_OF_OPERATING_HOURS` | 공원 운영 시간(10:00~22:00) 밖의 슬롯입니다. |
| 400 | `SLOT_START_IN_PAST` | 지난 시각에는 슬롯을 만들 수 없습니다. |
| 400 | `INVALID_SLOT_STATUS` | 허용되지 않은 슬롯 상태입니다. |
| 400 | `SLOT_STATUS_SAME` | 현재 상태와 같습니다. |
| 409 | `SLOT_TIME_OVERLAP` | 같은 날 다른 슬롯과 시간이 겹칩니다. |
| 409 | `SLOT_ALREADY_ENDED` | 이미 종료된 슬롯은 수정·삭제할 수 없습니다. |
| 409 | `SLOT_ALREADY_CANCELLED` | 이미 취소된 슬롯입니다. |
| 409 | `SLOT_HAS_RESERVATIONS` | 예약이 있는 슬롯입니다. 기존 예약을 먼저 처리해 주세요. |
| 409 | `SLOT_CAPACITY_BELOW_RESERVED` | 예약된 인원보다 적게 정원을 줄일 수 없습니다. |
| 409 | `SLOT_FULL` | 잔여 정원이 없어 OPEN으로 바꿀 수 없습니다. |
| 409 | `SLOT_RESERVATION_NOT_ALLOWED_STATUS` | 예약 가능한 슬롯 상태가 아닙니다. |
| 409 | `SLOT_CAPACITY_ACQUISITION_FAILED` | 슬롯이 마감되었거나 잔여 정원이 없어 예약할 수 없습니다. |

## 구현 근거

[AdminAttractionSlotController.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/controller/AdminAttractionSlotController.java), [AttractionSlotController.java](../../src/main/java/com/example/attractionreservation/domain/attractionSlot/controller/AttractionSlotController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
