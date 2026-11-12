# 회원 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

회원가입·본인 정보 관리·탈퇴와 관리자 회원 조회·상태 변경 API다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| POST | `/api/users/signup` | 회원가입 | 공개 + CSRF | 201 |
| GET | `/api/users/me` | 내 정보 조회 | 로그인 | 200 |
| PATCH | `/api/users/me` | 닉네임 수정 | 로그인 | 200 |
| PATCH | `/api/users/me/password` | 비밀번호 변경 | 로그인 | 200 |
| DELETE | `/api/users/me` | 회원 탈퇴 | 로그인 | 200 |
| GET | `/api/admin/users` | 회원 목록 | ADMIN | 200 |
| GET | `/api/admin/users/{userId}` | 회원 상세 | ADMIN | 200 |
| PATCH | `/api/admin/users/{userId}/status` | 회원 상태 변경 | ADMIN | 200 |

## 회원가입

```http
POST /api/users/signup
```

- 접근: **공개 + CSRF**.
- 성공: **201**. `data`: [CreateUserResponse](#schema-createuserresponse).
- 요청 본문: [CreateUserRequest](#schema-createuserrequest) (`application/json`).

일반 가입은 USER / ACTIVE / LOCAL로 생성한다. 이메일 중복은 409. 비밀번호는 공백 없이 8~20자, 영문·숫자·특수문자를 각각 1개 이상 포함한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "email": "member@example.test",
  "password": "Example123!",
  "nickname": "방문회원"
}
```

## 내 정보 조회

```http
GET /api/users/me
```

- 접근: **로그인**.
- 성공: **200**. `data`: [GetUserResponse](#schema-getuserresponse).
- 요청 본문: 없음.

provider는 LOCAL, KAKAO, GOOGLE이며 소셜 회원의 비밀번호 변경 UI 구분에 활용한다.

## 닉네임 수정

```http
PATCH /api/users/me
```

- 접근: **로그인**.
- 성공: **200**. `data`: [UpdateUserResponse](#schema-updateuserresponse).
- 요청 본문: [UpdateUserRequest](#schema-updateuserrequest) (`application/json`).

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "nickname": "방문회원"
}
```

## 비밀번호 변경

```http
PATCH /api/users/me/password
```

- 접근: **로그인**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: [UpdatePasswordRequest](#schema-updatepasswordrequest) (`application/json`).

currentPassword를 확인한다. 현재 비밀번호가 없거나 맞지 않으면 변경할 수 없다. 별도의 소셜 비밀번호 등록 API는 없다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "currentPassword": "Example123!",
  "newPassword": "Changed123!"
}
```

## 회원 탈퇴

```http
DELETE /api/users/me
```

- 접근: **로그인**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 없음.

본문·비밀번호 입력을 받지 않는다. 오늘 이후의 UNUSED 티켓 또는 COMPLETED 외 상태의 환불이 있으면 409로 보류한다. 시작 전 RESERVED 예약을 취소하고 정원을 복구하며 WITHDRAWN으로 변경한다. Refresh Token을 폐기하고 쿠키를 삭제한다.

## 회원 목록

```http
GET /api/admin/users
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchUserListResponse](#schema-searchuserlistresponse).
- 요청 본문: 없음.
- Query: [SearchUserCondition](#schema-searchusercondition).

기본 page=0, size=10. createdAt DESC, id DESC 고정 정렬.

## 회원 상세

```http
GET /api/admin/users/{userId}
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [SearchUserResponse](#schema-searchuserresponse).
- 요청 본문: 없음.
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

## 회원 상태 변경

```http
PATCH /api/admin/users/{userId}/status
```

- 접근: **ADMIN**.
- 성공: **200**. `data`: [UpdateUserStatusResponse](#schema-updateuserstatusresponse).
- 요청 본문: [UpdateUserStatusRequest](#schema-updateuserstatusrequest) (`application/json`).
- Path 변수: 위 경로의 `{...}` 값은 필수. `pgId`는 문자열이고 나머지 ID는 정수다. 실제 존재하고 접근 가능한 ID를 사용한다.

같은 상태 변경은 409. ACTIVE → INACTIVE/SUSPENDED, INACTIVE/SUSPENDED → ACTIVE만 허용한다. WITHDRAWN은 변경할 수 없고 이 API로 탈퇴 처리할 수도 없다. SUSPENDED로 변경하면 Refresh Token을 폐기한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "status": "ACTIVE"
}
```

## 대표 성공 응답 예시

다음은 `POST /api/users/signup`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "id": 1,
    "email": "member@example.test",
    "nickname": "방문회원",
    "role": "USER",
    "status": "ACTIVE"
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-createuserrequest"></a>

### CreateUserRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `email` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 50; 이메일 형식 | 이메일 |
| `password` | `String` | 필수; 빈 문자열·공백 불가; 8~20자, 영문·숫자·특수문자, 공백 불가 | 비밀번호 |
| `nickname` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 50 | 닉네임 |

근거: [CreateUserRequest.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/CreateUserRequest.java).

<a id="schema-createuserresponse"></a>

### CreateUserResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `role` | `String` | — | 회원 권한 |
| `status` | `String` | — | 상태 |

근거: [CreateUserResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/CreateUserResponse.java).

<a id="schema-getuserresponse"></a>

### GetUserResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `role` | `String` | — | 회원 권한 |
| `status` | `String` | — | 상태 |
| `provider` | `String` | — | 가입 방식 |

근거: [GetUserResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/GetUserResponse.java).

<a id="schema-updateuserrequest"></a>

### UpdateUserRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `nickname` | `String` | 필수; 빈 문자열·공백 불가; 크기 max = 50 | 닉네임 |

근거: [UpdateUserRequest.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/UpdateUserRequest.java).

<a id="schema-updateuserresponse"></a>

### UpdateUserResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |

근거: [UpdateUserResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/UpdateUserResponse.java).

<a id="schema-updatepasswordrequest"></a>

### UpdatePasswordRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `currentPassword` | `String` | 필수; 빈 문자열·공백 불가 | 현재 비밀번호 |
| `newPassword` | `String` | 필수; 빈 문자열·공백 불가; 8~20자, 영문·숫자·특수문자, 공백 불가 | 새 비밀번호 |

근거: [UpdatePasswordRequest.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/UpdatePasswordRequest.java).

<a id="schema-searchuserlistresponse"></a>

### SearchUserListResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `content` | `List<Item>` | — | 현재 페이지 항목 목록 |
| `page` | `int` | — | 현재 페이지 번호(0부터) |
| `size` | `int` | — | 페이지 크기 |
| `totalElements` | `long` | — | 전체 항목 수 |
| `totalPages` | `int` | — | 전체 페이지 수 |

근거: [SearchUserListResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/SearchUserListResponse.java).

<a id="schema-searchuserlistresponse-item"></a>

### SearchUserListResponse.Item

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `role` | `Role` | — | 회원 권한 |
| `status` | `UserStatus` | — | 상태 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |

근거: [SearchUserListResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/SearchUserListResponse.java).

<a id="schema-searchusercondition"></a>

### SearchUserCondition

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `page` | `Integer` | 생략 시 0; 최소 0 | 현재 페이지 번호(0부터) |
| `size` | `Integer` | 생략 시 10; 최소 1 | 페이지 크기 |

근거: [SearchUserCondition.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/SearchUserCondition.java).

<a id="schema-searchuserresponse"></a>

### SearchUserResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `role` | `Role` | — | 회원 권한 |
| `status` | `UserStatus` | — | 상태 |
| `createdAt` | `LocalDateTime` | — | 생성 시각 |
| `updatedAt` | `LocalDateTime` | — | 수정 시각 |

근거: [SearchUserResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/SearchUserResponse.java).

<a id="schema-updateuserstatusrequest"></a>

### UpdateUserStatusRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `status` | `UserStatus` | 필수 | 상태 |

근거: [UpdateUserStatusRequest.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/UpdateUserStatusRequest.java).

<a id="schema-updateuserstatusresponse"></a>

### UpdateUserStatusResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `id` | `Long` | — | 식별자 |
| `email` | `String` | — | 이메일 |
| `nickname` | `String` | — | 닉네임 |
| `status` | `UserStatus` | — | 상태 |

근거: [UpdateUserStatusResponse.java](../../src/main/java/com/example/attractionreservation/domain/user/dto/UpdateUserStatusResponse.java).

## enum 값

요청에서 허용하는 상태 전이는 endpoint 설명을 우선한다. enum에 존재한다고 모두 변경 요청에 허용되는 것은 아니다.

| 타입 | 값 |
|---|---|
| `Role` | `USER`, `ADMIN` |
| `UserStatus` | `ACTIVE`, `INACTIVE`, `SUSPENDED`, `WITHDRAWN` |

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 409 | `DUPLICATE_EMAIL` | 이미 사용 중인 이메일입니다. |
| 403 | `USER_NOT_WITHDRAWABLE` | 현재 회원 상태에서는 탈퇴할 수 없습니다. |
| 409 | `USER_WITHDRAWAL_UNUSED_TICKET` | 미사용 입장권이 있어 탈퇴할 수 없습니다. 환불 후 다시 시도해주세요. |
| 409 | `USER_WITHDRAWAL_INCOMPLETE_REFUND` | 완료되지 않은 환불이 있어 탈퇴할 수 없습니다. |
| 400 | `CURRENT_PASSWORD_MISMATCH` | 현재 비밀번호가 일치하지 않습니다. |
| 404 | `USER_NOT_FOUND` | 유저를 찾을 수 없습니다. |
| 409 | `USER_STATUS_SAME` | 현재 회원 상태와 동일합니다. |
| 409 | `USER_INVALID_STATUS_TRANSITION` | 허용되지 않는 회원 상태 변경입니다. |
| 403 | `USER_NOT_ACTIVE` | 이용할 수 없는 회원 상태입니다. |

## 구현 근거

[AdminUserController.java](../../src/main/java/com/example/attractionreservation/domain/user/controller/AdminUserController.java), [UserController.java](../../src/main/java/com/example/attractionreservation/domain/user/controller/UserController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
