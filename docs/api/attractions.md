# 어트랙션 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

목록·상세는 공개 API이고 질문 API는 로그인이 필요하다. 관리자 정보 수정과 운영 상태 변경은 분리되어 있다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/attractions` | 어트랙션 목록 | 공개 | 200 |
| GET | `/api/attractions/{attractionId}` | 어트랙션 상세 | 공개 | 200 |
| POST | `/api/attractions/questions` | 어트랙션 질문 | 로그인 | 200 |
| POST | `/api/admin/attractions` | 어트랙션 등록 | ADMIN | 201 |
| PATCH | `/api/admin/attractions/{attractionId}` | 일반 정보 수정 | ADMIN | 200 |
| PATCH | `/api/admin/attractions/{attractionId}/status` | 운영 상태 변경 | ADMIN | 200 |
| DELETE | `/api/admin/attractions/{attractionId}` | 어트랙션 삭제 | ADMIN | 200 |

## 어트랙션 목록

```http
GET /api/attractions
```

- 접근: **공개**.
- 성공: **200**. `data`: [SearchAttractionListResponse](#schema-searchattractionlistresponse).
- 요청 본문: 없음.
- Query: [SearchAttractionCondition](#schema-searchattractioncondition).

기본 page=0, size=10, sort=NAME, reservable=false, operating=false. size 최대 50. reservable=true는 오늘 예약 가능한 슬롯이 있는 어트랙션을 선택한다. nextSlot은 오늘 가장 빠른 예약 가능 슬롯이며 없으면 null일 수 있다.

## 어트랙션 상세

```http
GET /api/attractions/{attractionId}
```

- 접근: **공개**.
- 성공: **200**. `data`: [SearchAttractionResponse](#schema-searchattractionresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

삭제된 어트랙션은 404. 상세 캐시가 적용되어 있으나 클라이언트는 cache key에 의존하지 않는다.

## 어트랙션 질문

```http
POST /api/attractions/questions
```

- 접근: **로그인**.
- 성공: **200**. `data`: [CreateAttractionQuestionResponse](#schema-createattractionquestionresponse).
- 요청 본문: [CreateAttractionQuestionRequest](#schema-createattractionquestionrequest) (`application/json`).

키 제한·운영 시간·운영 상태 질문을 처리한다. 정상 답변 ANSWERED, 대상 재확인 NEED_CLARIFICATION, 미지원 UNSUPPORTED 모두 HTTP 200이고 data.type으로 구분한다. 외부 분류기 이용 불가는 503.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "question": "예시"
}
```

## 어트랙션 등록

```http
POST /api/admin/attractions
```

- 접근: **ADMIN**.
- 성공: **201**. `data`: [CreateAttractionResponse](#schema-createattractionresponse).
- 요청 본문: [CreateAttractionRequest](#schema-createattractionrequest) (`application/json`).

등록 상태는 OPERATING이다. 비삭제 어트랙션 이름은 유일하며 삭제된 이름은 재사용 가능하다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "name": "롤러코스터",
  "description": "상품 또는 시설 설명",
  "location": "A구역",
  "durationMinutes": 10,
  "entranceMoveMinutes": 5
}
```

## 일반 정보 수정

```http
PATCH /api/admin/attractions/{attractionId}
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchAttractionResponse](#schema-searchattractionresponse).
- 요청 본문: [UpdateAttractionRequest](#schema-updateattractionrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

보내지 않은 필드는 유지한다. 취소되지 않았고 endsAt이 현재 이후인 슬롯이 있으면 durationMinutes의 실제 변경은 409로 거부한다. 허용된 소요 시간 변경은 기존 슬롯을 일괄 변경하지 않고 이후 생성 슬롯에 반영한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "name": "롤러코스터",
  "description": "상품 또는 시설 설명",
  "location": "A구역",
  "durationMinutes": 10,
  "entranceMoveMinutes": 5
}
```

## 운영 상태 변경

```http
PATCH /api/admin/attractions/{attractionId}/status
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateAttractionStatusResponse](#schema-updateattractionstatusresponse).
- 요청 본문: [UpdateAttractionStatusRequest](#schema-updateattractionstatusrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

OPERATING, MAINTENANCE, SUSPENDED만 요청 가능하다. 같은 상태는 400. 운영중이 아닌 상태로 바꿀 때 RESERVED이면서 startsAt > now인 예약이 있으면 409. DELETED는 삭제 API로만 처리한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "operationStatus": "OPERATING"
}
```

## 어트랙션 삭제

```http
DELETE /api/admin/attractions/{attractionId}
```

- 접근: **ADMIN**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

이용 예정 예약이 있으면 409. 소프트 삭제하며 기본 조회에서 제외한다.

## 대표 성공 응답 예시

다음은 `GET /api/attractions`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "content": [
      {
        "attractionId": 1,
        "name": "롤러코스터",
        "description": "상품 또는 시설 설명",
        "location": "A구역",
        "durationMinutes": 10,
        "operationStatus": "OPERATING",
        "nextSlot": {
          "slotId": 1,
          "startsAt": "2026-11-13T14:00:00",
          "remainingCount": 19
        }
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-searchattractionlistresponse"></a>

### SearchAttractionListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<AttractionItem>` | — | 현재 페이지 항목 목록 |
| `page` | `Integer` | — | 현재 페이지 번호(0부터) |
| `size` | `Integer` | — | 페이지 크기 |
| `totalElements` | `Long` | — | 전체 항목 수 |
| `totalPages` | `Integer` | — | 전체 페이지 수 |

근거: [SearchAttractionListResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/SearchAttractionListResponse.java).

<a id="schema-searchattractionlistresponse-attractionitem"></a>

### SearchAttractionListResponse.AttractionItem

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `name` | `String` | — | 이름 |
| `description` | `String` | — | 설명 |
| `location` | `String` | — | 위치 또는 구역 |
| `durationMinutes` | `Integer` | — | 탑승 소요 시간(분) |
| `operationStatus` | `OperationStatus` | — | 어트랙션 운영 상태 |
| `nextSlot` | [NextSlot](#schema-searchattractionlistresponse-nextslot) | — | 오늘 가장 빠른 예약 가능 슬롯 |

근거: [SearchAttractionListResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/SearchAttractionListResponse.java).

<a id="schema-searchattractionlistresponse-nextslot"></a>

### SearchAttractionListResponse.NextSlot

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `slotId` | `Long` | — | 슬롯 ID |
| `startsAt` | `LocalDateTime` | — | 슬롯 시작 KST |
| `remainingCount` | `Integer` | — | 잔여 정원 |

근거: [SearchAttractionListResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/SearchAttractionListResponse.java).

<a id="schema-searchattractioncondition"></a>

### SearchAttractionCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `page` | `Integer` | 생략 시 0; 최소 0 | 현재 페이지 번호(0부터) |
| `size` | `Integer` | 생략 시 10; 최소 1; 최대 50 | 페이지 크기 |
| `keyword` | `String` | 선택; 크기 max = 50 | 이름 검색어 |
| `sort` | `String` | 선택; NAME 또는 LATEST | 정렬 기준 |
| `reservable` | `Boolean` | 선택 | 조회 시점 예약 가능 여부 또는 검색 조건 |
| `operating` | `Boolean` | 선택 | 운영중 필터 |

근거: [SearchAttractionCondition.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/SearchAttractionCondition.java).

<a id="schema-searchattractionresponse"></a>

### SearchAttractionResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `name` | `String` | — | 이름 |
| `description` | `String` | — | 설명 |
| `location` | `String` | — | 위치 또는 구역 |
| `durationMinutes` | `Integer` | — | 탑승 소요 시간(분) |
| `entranceMoveMinutes` | `Integer` | — | 정문 기준 이동 시간(분) |
| `operationStatus` | `OperationStatus` | — | 어트랙션 운영 상태 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |
| `updatedAt` | `LocalDateTime` | — | 수정 시각 |

근거: [SearchAttractionResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/SearchAttractionResponse.java).

<a id="schema-createattractionquestionrequest"></a>

### CreateAttractionQuestionRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `question` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 어트랙션에 관한 자연어 질문 |

근거: [CreateAttractionQuestionRequest.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/CreateAttractionQuestionRequest.java).

<a id="schema-createattractionquestionresponse"></a>

### CreateAttractionQuestionResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `type` | `QuestionAnswerType` | — | 답변 유형 |
| `intent` | `QuestionIntent` | — | 분류된 질문 의도. 결과에 따라 null일 수 있음 |
| `attractionId` | `Long` | — | 어트랙션 ID |
| `attractionName` | `String` | — | 어트랙션 이름 |
| `answer` | `String` | — | 답변 또는 되묻기·미지원 안내 문구 |
| `candidates` | `List<AttractionCandidate>` | — | 되묻기에서 선택할 어트랙션 후보 목록 |

근거: [CreateAttractionQuestionResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/CreateAttractionQuestionResponse.java).

<a id="schema-attractioncandidate"></a>

### AttractionCandidate

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `name` | `String` | — | 이름 |

근거: [AttractionCandidate.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/AttractionCandidate.java).

<a id="schema-createattractionrequest"></a>

### CreateAttractionRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 50 | 이름 |
| `description` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 200 | 설명 |
| `location` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 50 | 위치 또는 구역 |
| `durationMinutes` | `Integer` | 필수; 최소 1 | 탑승 소요 시간(분) |
| `entranceMoveMinutes` | `Integer` | 필수; 최소 1 | 정문 기준 이동 시간(분) |

근거: [CreateAttractionRequest.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/CreateAttractionRequest.java).

<a id="schema-createattractionresponse"></a>

### CreateAttractionResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `name` | `String` | — | 이름 |
| `description` | `String` | — | 설명 |
| `location` | `String` | — | 위치 또는 구역 |
| `durationMinutes` | `Integer` | — | 탑승 소요 시간(분) |
| `entranceMoveMinutes` | `Integer` | — | 정문 기준 이동 시간(분) |
| `operationStatus` | `OperationStatus` | — | 어트랙션 운영 상태 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |

근거: [CreateAttractionResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/CreateAttractionResponse.java).

<a id="schema-updateattractionrequest"></a>

### UpdateAttractionRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `name` | `String` | 선택; 크기 max = 50; 공백만 입력 불가 | 이름 |
| `description` | `String` | 선택; 크기 max = 200; 공백만 입력 불가 | 설명 |
| `location` | `String` | 선택; 크기 max = 50; 공백만 입력 불가 | 위치 또는 구역 |
| `durationMinutes` | `Integer` | 선택; 최소 1 | 탑승 소요 시간(분) |
| `entranceMoveMinutes` | `Integer` | 선택; 최소 1 | 정문 기준 이동 시간(분) |

근거: [UpdateAttractionRequest.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/UpdateAttractionRequest.java).

<a id="schema-updateattractionstatusrequest"></a>

### UpdateAttractionStatusRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `operationStatus` | `String` | 필수; 빈 문자열·공백 불가 | 어트랙션 운영 상태 |

근거: [UpdateAttractionStatusRequest.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/UpdateAttractionStatusRequest.java).

<a id="schema-updateattractionstatusresponse"></a>

### UpdateAttractionStatusResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `attractionId` | `Long` | — | 어트랙션 ID |
| `previousStatus` | `OperationStatus` | — | 변경 전 운영 상태 |
| `operationStatus` | `OperationStatus` | — | 어트랙션 운영 상태 |
| `updatedAt` | `LocalDateTime` | — | 수정 시각 |

근거: [UpdateAttractionStatusResponse.java](../../src/main/java/com/example/attractionreservation/domain/attraction/dto/UpdateAttractionStatusResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `OperationStatus` | `OPERATING`, `MAINTENANCE`, `SUSPENDED`, `DELETED` |
| `QuestionAnswerType` | `ANSWERED`, `NEED_CLARIFICATION`, `UNSUPPORTED` |
| `QuestionIntent` | `HEIGHT_LIMIT`, `OPERATING_HOURS`, `OPERATION_STATUS`, `OTHER` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 404 | `ATTRACTION_NOT_FOUND` | 어트랙션을 찾을 수 없습니다. |
| 409 | `ATTRACTION_NAME_DUPLICATED` | 이미 존재하는 어트랙션명입니다. |
| 400 | `INVALID_ATTRACTION_STATUS` | 허용되지 않은 운영 상태입니다. |
| 400 | `ATTRACTION_STATUS_SAME` | 현재 상태와 같습니다. |
| 409 | `ATTRACTION_HAS_UPCOMING_RESERVATIONS` | 이용 예정 예약이 있어 처리할 수 없습니다. 예약을 먼저 처리해 주세요. |
| 409 | `ATTRACTION_NOT_OPERATING` | 현재 운영 중인 어트랙션이 아닙니다. |
| 409 | `ATTRACTION_DURATION_CHANGE_NOT_ALLOWED` | 운영 예정이거나 진행 중인 슬롯이 있어 소요 시간을 변경할 수 없습니다. |
| 503 | `ATTRACTION_QUESTION_UNAVAILABLE` | 지금은 어트랙션 질문에 답할 수 없습니다. 잠시 후 다시 시도해 주세요. |

## 구현 근거

[AdminAttractionController.java](../../src/main/java/com/example/attractionreservation/domain/attraction/controller/AdminAttractionController.java), [AttractionController.java](../../src/main/java/com/example/attractionreservation/domain/attraction/controller/AttractionController.java), [AttractionQuestionController.java](../../src/main/java/com/example/attractionreservation/domain/attraction/controller/AttractionQuestionController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
