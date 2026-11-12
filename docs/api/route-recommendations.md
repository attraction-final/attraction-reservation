# 경로 추천 API (현재 위치 기반) 및 일괄 예약

## 공통 사항

세 API 모두 로그인이 필요하다.

```http
Authorization: Bearer {accessToken}
```

미인증 요청은 HTTP 401로 거절한다. 성공 응답은 다음 구조를 사용한다.

```json
{
  "success": true,
  "message": "처리 결과 메시지",
  "data": {}
}
```

실패 응답에는 `errorCode` 필드가 없고 `success: false`와 `message`만 담긴다. 아래 표의 "오류 코드"는 서버 내부 이름이며 화면에서는 HTTP 상태와 `message`로 구분한다. 시각은 KST 기준 `LocalDateTime`(예: `2026-11-11T13:00:00`)이다.

추천은 예약을 만들지 않는다. 사용자가 추천 결과를 확인한 뒤 일괄 예약 API를 호출해야 예약이 생긴다.

## 현재 위치 기반 경로 추천 생성

```http
POST /api/route-recommendations
Content-Type: application/json
```

이용 시작 시각은 현재 시각(분 단위 올림), 종료 시각은 입장권 방문일 22:00(폐장)으로 서버가 정한다. 클라이언트는 시간 범위를 보내지 않는다. 서버는 Geolocation API를 호출하지 않고, 브라우저가 측정한 값(`navigator.geolocation.getCurrentPosition`)을 받기만 한다. 위치 값은 응답에만 돌려주고 저장하지 않는다.

### 요청

| 필드 | 필수 | 검증 |
|---|---|---|
| `ticketId` | 필수 | 1 이상 |
| `attractionIds` | 필수 | 1~5개, 각 원소는 null이 아닌 양수 |
| `latitude` | 필수 | -90 ~ 90 |
| `longitude` | 필수 | -180 ~ 180 |
| `accuracy` | 필수 | 0 이상 (오차 반경, m) |
| `altitude` | 선택 | 범위 제한 없음 |
| `altitudeAccuracy` | 선택 | 0 이상 |
| `heading` | 선택 | 0 이상 360 미만. 정지 상태의 NaN은 JSON에서 null로 보낸다 |
| `speed` | 선택 | 0 이상 (m/s) |
| `timestamp` | 선택 | 양수. `position.timestamp`(Unix epoch 밀리초) |

서비스 검증 규칙은 다음과 같다.

- 입장권은 본인 소유이고, 입장 처리되어 이용 중(`IN_USE`)이며, 방문일이 오늘이어야 한다.
- 같은 어트랙션 ID를 중복해서 보낼 수 없다.
- `accuracy`가 100m 이상이면 거절한다. (기본 설정값)
- 선택한 어트랙션 중 가장 가까운 곳까지 직선거리가 2,000m를 넘으면 거절한다. (기본 설정값)
- 아직 끝나지 않은 `RESERVED` 예약이 하나라도 있으면 추천하지 않는다.
- 현재 시각(올림)이 22:00 이후면 거절한다.
- 좌표가 등록되지 않은 어트랙션은 오류가 아니라 `LOCATION_NOT_FOUND` 사유로 제외한다.

요청 예시:

```json
{
  "ticketId": 10,
  "attractionIds": [1, 2, 3],
  "latitude": 37.2930,
  "longitude": 127.2010,
  "accuracy": 30.0,
  "altitude": 45.2,
  "altitudeAccuracy": 10.0,
  "heading": 180.0,
  "speed": 1.5,
  "timestamp": 1762412345000
}
```

### 응답

- `201 Created`: 결과가 `FULL`(선택한 모두 방문) 또는 `PARTIAL`(일부만 방문)이다. 추천을 저장하고 `recommendationId`를 돌려준다.
    - 메시지: `선택하신 {선택 수}개의 어트랙션 중 {추천 수}개를 방문할 수 있는 경로를 추천했습니다.`
- `200 OK`: 추천 가능한 경로가 없다(`NONE`). 저장하지 않으며 `recommendationId`, `estimatedEndAt`, `createdAt`은 null이다. 제외 사유는 `excludedAttractions`에 담긴다.
    - 메시지: `선택한 조건에서 추천 가능한 경로가 없습니다.`

`data`는 `{ recommendation, location }` 형태다.

`recommendation`:

| 필드 | 설명 |
|---|---|
| `recommendationId` | 저장된 추천 ID. `NONE`이면 null |
| `ticketId` | 요청한 입장권 ID |
| `startAt`, `endAt` | 이용 시작(현재 시각 분 단위 올림), 종료(방문일 22:00) |
| `resultType` | `FULL`, `PARTIAL`, `NONE` |
| `totalSelectedAttractions` | 선택한 어트랙션 수 |
| `totalRecommendedAttractions` | 방문하는 어트랙션 수 |
| `totalMoveMinutes`, `totalWaitMinutes`, `totalRideMinutes`, `totalDurationMinutes` | 예상 이동·대기·탑승·전체 소요 시간(분) |
| `estimatedEndAt` | 예상 종료 시각. `NONE`이면 null |
| `createdAt` | 추천 생성 시각. `NONE`이면 null |
| `estimatedValues` | 항상 `true` |
| `visits` | 방문 순서대로의 목록 |
| `excludedAttractions` | 제외된 어트랙션 목록 |

`visits[]`: `sequence`, `attractionId`, `attractionName`, `attractionSlotId`, `estimatedMoveMinutes`, `moveEstimated`, `estimatedArrivalAt`, `estimatedWaitMinutes`, `startsAt`, `endsAt`, `estimatedRideMinutes`.

`excludedAttractions[]`: `attractionId`, `attractionName`, `reasonCode`, `reason`.

`reasonCode`: `NOT_OPERATING`, `OUT_OF_TIME_RANGE`, `SLOT_NOT_OPEN`, `SLOT_FULL`, `RESERVATION_CLOSED`, `SLOT_MANUALLY_CLOSED`, `LOCATION_NOT_FOUND`, `TIME_CONFLICT`. `OUT_OF_TIME_RANGE`의 메시지는 `지금부터 폐장 전까지 운영하는 슬롯이 없습니다.`이다.

`location`: 요청 위치를 그대로 돌려주고 경고를 덧붙인다.

| 필드 | 설명 |
|---|---|
| `latitude`, `longitude`, `accuracy`, `altitude`, `altitudeAccuracy`, `heading`, `speed` | 요청 값 그대로 |
| `measuredAt` | `timestamp`를 KST로 바꾼 값. 요청에 없으면 null |
| `warnings` | `STALE_POSITION`: 측정 시각이 기준(기본 5분)보다 오래됨. 없으면 빈 배열 |

### 예상값 안내

`estimatedMoveMinutes`, 대기 시간, 도착 시각(`estimatedArrivalAt`) 등은 모두 예상값이며 실측 도보 시간이 아니다. 그래서 `estimatedValues`는 항상 `true`다. 첫 구간(현재 위치에서 첫 어트랙션까지)은 좌표 직선거리에 우회 계수와 평균 보행 속도를 적용해 추정하므로 첫 방문의 `moveEstimated`는 항상 `true`다. 이후 구간은 구간 데이터의 추정 여부를 따른다. 화면에는 "예상"임을 표시해야 한다.

### 오류

| HTTP 상태 | 오류 코드 | 상황 |
|---|---|---|
| 400 | `INVALID_INPUT_VALUE` | 위 요청 검증 위반(필드별 오류는 `data`에 담길 수 있음) |
| 400 | `DUPLICATE_ROUTE_ATTRACTION` | 같은 어트랙션 중복 선택 |
| 400 | `LOCATION_ACCURACY_TOO_LOW` | 위치 정확도 기준 초과 |
| 400 | `LOCATION_OUT_OF_SERVICE_AREA` | 가장 가까운 어트랙션까지 너무 멂 |
| 400 | `INVALID_TIME_RANGE` | 현재 시각이 폐장 시각 이후라 추천에 쓸 시간이 없음 |
| 400 | `TICKET_ENTRY_DATE_MISMATCH` | 입장권 방문일이 오늘이 아님 |
| 401 | - | 미인증 |
| 403 | `TICKET_ACCESS_DENIED` | 다른 회원의 입장권 |
| 404 | `TICKET_NOT_FOUND` | 입장권 없음 |
| 404 | `ATTRACTION_NOT_FOUND` | 존재하지 않거나 삭제된 어트랙션 포함. 메시지에 없는 ID 목록이 담김 |
| 409 | `TICKET_RESERVATION_NOT_ALLOWED_STATUS` | 입장 처리되지 않았거나(`UNUSED`) 이미 사용·만료·환불된 입장권 |
| 409 | `ROUTE_RECOMMENDATION_BLOCKED` | 이용 예정인 어트랙션 예약이 있음 |

## 추천 경로 일괄 예약

```http
POST /api/route-recommendations/{recommendationId}/reservations
```

요청 본문은 없다. 저장된 추천에 포함된 방문(`visits`)만 예약하며, 제외된 어트랙션은 예약하지 않는다. 어트랙션 ID 오름차순으로 예약을 시도한다.

### 전부 성공해야만 예약된다

하나의 트랜잭션으로 처리한다. 방문 중 하나라도 예약에 실패하면 이미 만든 예약과 슬롯 정원 차감까지 모두 취소(롤백)하고 HTTP 409를 반환한다. 일부만 예약되는 경우는 없다. 실패 응답에는 처리 순서상 처음 실패한 방문 하나만 담기며, 이후 방문은 시도하지 않으므로 다른 방문의 실패 여부는 알 수 없다. 사용자가 조건을 바꿔 추천을 다시 받아야 한다.

### 응답 (201 Created)

메시지: `추천 경로의 어트랙션 {n}개를 예약했습니다.`

```json
{
  "success": true,
  "message": "추천 경로의 어트랙션 2개를 예약했습니다.",
  "data": {
    "recommendationId": 100,
    "ticketId": 10,
    "reservedCount": 2,
    "reservations": [
      {
        "sequence": 1,
        "attractionId": 3,
        "attractionName": "아마존 익스프레스",
        "slotId": 3001,
        "reservationId": 9001,
        "reservationNumber": "R-9001",
        "startsAt": "2026-11-11T13:00:00",
        "endsAt": "2026-11-11T13:10:00"
      }
    ]
  }
}
```

`reservations`는 방문 순서(`sequence` 오름차순)다. `startsAt`, `endsAt`은 추천 당시 값이 아니라 실제 예약된 슬롯의 값이다.

### 오류

| HTTP 상태 | 오류 코드 | 상황 |
|---|---|---|
| 401 | - | 미인증 |
| 403 | `NOT_ROUTE_OWNER` | 다른 회원의 추천 경로 |
| 404 | `ROUTE_RECOMMENDATION_NOT_FOUND` | 존재하지 않는 추천 경로 |
| 409 | `ROUTE_RESERVATION_FAILED` | 방문 중 하나라도 예약 실패. 전체 취소됨 |

409 `ROUTE_RESERVATION_FAILED`의 `message` 형식:

```text
{N}번째 방문 '{어트랙션 이름}' 예약에 실패해 일괄 예약을 모두 취소했습니다. 사유: {예약 실패 사유 메시지}
```

`{N}`은 추천의 방문 순서(`sequence`)다. 사유는 예약 API가 돌려주는 오류 메시지(슬롯 매진, 예약 마감 시각 경과, 기존 예약과 시간 겹침, 입장권 상태 등)이며, 같은 슬롯 동시 예약 시 `이미 예약한 슬롯입니다.`, 잠금 대기 시간 초과·교착 시 `다른 요청에 의해 데이터가 변경되었습니다. 다시 시도해주세요.`가 사유로 담긴다. 잠금 충돌도 같은 409 `ROUTE_RESERVATION_FAILED`로 응답한다.

## 추천 가능한 어트랙션 목록

```http
GET /api/route-recommendations/attractions
```

경로 추천에 고를 수 있는 어트랙션 목록이다. 운영 중이고 좌표가 등록된 어트랙션만 포함한다. 좌표가 없는 어트랙션은 추천에서 제외되므로 화면이 미리 거를 때 쓴다. 사진·운영 상태 등 화면 정보는 어트랙션 목록 API에서 가져온다.

메시지: `추천 가능한 어트랙션 목록을 조회했습니다.`

```json
{
  "success": true,
  "message": "추천 가능한 어트랙션 목록을 조회했습니다.",
  "data": [
    { "attractionId": 1, "attractionName": "범퍼카", "latitude": 37.2930, "longitude": 127.2010 }
  ]
}
```

고른 어트랙션끼리의 구간 데이터는 조합마다 달라 보장하지 않는다. 구간이 없거나 비활성이면 그 이동이 필요한 방문 순서는 쓰지 않으며, 그 때문에 경로에 넣지 못한 어트랙션은 `TIME_CONFLICT`로 제외된다.

## 이전 API 대비 변경점 (프론트 수정 필요)

- `ticketId`가 필수다.
- `attractionIds`는 1~5개다.
- `startAt`, `endAt`을 받지 않는다. 시작은 현재 시각, 종료는 방문일 22:00으로 서버가 정한다.
- 현재 위치 필드 `latitude`, `longitude`, `accuracy`가 필수다.
- 추천 응답이 `recommendation`으로 감싸졌고, 위치 정보가 `location`으로 함께 온다. 추천 결과는 `data.recommendation.*`에서 읽는다.
- 추천 가능한 경로가 없으면(`NONE`) 201이 아니라 200으로 응답하며 저장하지 않는다.
- 추천 결과를 예약할 때 `POST /api/reservations`를 방문마다 반복 호출하지 말고, `recommendationId`로 일괄 예약 API(`POST /api/route-recommendations/{recommendationId}/reservations`)를 한 번 호출한다.