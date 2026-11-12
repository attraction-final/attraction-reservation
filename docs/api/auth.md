# 인증 API

[전체 API 목록](README.md) · [공통 규칙](common.md)

Access Token 발급·재발급, Refresh Token 쿠키, CSRF 토큰과 소셜 로그인 흐름을 설명한다.

## endpoint 목록

| Method | 경로 | 기능 | 접근 | 성공 HTTP |
|---|---|---|---|---|
| GET | `/api/auth/csrf` | CSRF 토큰 조회 | 공개 | 200 |
| POST | `/api/auth/login` | 이메일 로그인 | 공개 + CSRF | 200 |
| POST | `/api/auth/oauth2/exchange` | 소셜 로그인 코드 교환 | 공개 + CSRF | 200 |
| POST | `/api/auth/refresh` | Access Token 재발급 | Refresh Token 쿠키 + CSRF | 200 |
| POST | `/api/auth/logout` | 로그아웃 | 로그인 + Refresh Token 쿠키 + CSRF | 200 |

## CSRF 토큰 조회

```http
GET /api/auth/csrf
```

- 접근: **공개**.
- 성공: **200**. `data`: [CsrfResponse](#schema-csrfcontroller-csrfresponse).
- 요청 본문: 없음.

검증용 XSRF-TOKEN 쿠키와 응답 토큰을 함께 받는다. Cache-Control: no-store. 이후 보호된 POST 요청에 응답의 headerName/token을 사용한다.

## 이메일 로그인

```http
POST /api/auth/login
```

- 접근: **공개 + CSRF**.
- 성공: **200**. `data`: [CreateAuthLoginResponse](#schema-createauthloginresponse).
- 요청 본문: [CreateAuthLoginRequest](#schema-createauthloginrequest) (`application/json`).

ACTIVE인 LOCAL 회원만 가능하다. 성공 시 refreshToken HttpOnly 쿠키를 설정한다. accessToken만 JSON에 포함한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "email": "member@example.test",
  "password": "Example123!"
}
```

## 소셜 로그인 코드 교환

```http
POST /api/auth/oauth2/exchange
```

- 접근: **공개 + CSRF**.
- 성공: **200**. `data`: [CreateAuthLoginResponse](#schema-createauthloginresponse).
- 요청 본문: [CreateSocialLoginExchangeRequest](#schema-createsocialloginexchangerequest) (`application/json`).

code는 제공자의 인가 코드가 아닌 우리 서버가 발급한 일회용 코드다. 사용·만료된 코드는 401. 로그인과 같은 Refresh Token 쿠키를 발급하고 Cache-Control: no-store를 설정한다.

요청 예시(날짜·ID는 실제 이용 가능한 값으로 교체):

```json
{
  "code": "<one-time-code>"
}
```

## Access Token 재발급

```http
POST /api/auth/refresh
```

- 접근: **Refresh Token 쿠키 + CSRF**.
- 성공: **200**. `data`: [CreateAuthRefreshResponse](#schema-createauthrefreshresponse).
- 요청 본문: 없음.

Cookie: refreshToken={refreshToken} 필요. Redis 저장값과 비교한다. Access Token만 재발급하며 Refresh Token·쿠키 만료는 연장하지 않는다. 만료된 Access Token을 헤더로 보낼 필요가 없다.

## 로그아웃

```http
POST /api/auth/logout
```

- 접근: **로그인 + Refresh Token 쿠키 + CSRF**.
- 성공: **200**. 데이터 없음. `data` 키는 생략한다.
- 요청 본문: 없음.

Redis의 일치하는 Refresh Token을 폐기하고 동일한 경로의 쿠키를 Max-Age=0으로 지운다. Access Token 자체를 즉시 blacklist 처리하는 API는 아니다.

## 소셜 로그인 redirect 경로

`oauth` 프로필에서만 OAuth2 전용 filter chain이 활성화된다. 다음은 JSON API가 아닌 브라우저 redirect 경로다.

| Method | 경로 | 동작 |
|---|---|---|
| GET | `/oauth2/authorization/{registrationId}` | 제공자 로그인 시작. 설정된 `google`, `kakao` 사용 |
| GET | `/login/oauth2/code/{registrationId}` | 제공자 callback. 프론트가 직접 인가 코드를 교환하는 API가 아니다 |

성공 시 `app.oauth2.success-redirect-uri`에 일회용 `code` query를 붙여 redirect한다. 프론트는 이 코드를 POST `/api/auth/oauth2/exchange`로 교환한다. 실패 시 `app.oauth2.failure-redirect-uri`에 `error` query를 붙인다. redirect URL은 배포 설정값이며 이 문서에서 고정하지 않는다.

실패 redirect의 error 값은 `UNSUPPORTED_PROVIDER`, `INVALID_PROVIDER_USER_ID`, `EMAIL_REQUIRED`, `EMAIL_NOT_VERIFIED`, `EMAIL_ALREADY_EXISTS`, `USER_NOT_ACTIVE`, `INVALID_USER_INFO`, `AUTHENTICATION_FAILED` 중 하나다. API의 ErrorCode JSON 필드와는 다른 OAuth2 redirect query 계약이다.

## 토큰 응답 예시

```json
{"success":true,"message":"로그인에 성공했습니다.","data":{"accessToken":"<access-token>","tokenType":"Bearer","expiresIn":600}}
```

`expiresIn` 단위는 초다. 기본 Access Token 유효기간은 600초, Refresh Token은 7일이며 환경 설정으로 변경될 수 있다. 쿠키 속성과 브라우저 호출 순서는 [공통 규칙](common.md)을 따른다.

## 대표 성공 응답 예시

다음은 `GET /api/auth/csrf`의 JSON 구조 예시다. 시각·식별자·상태는 설명용이며 실제 응답은 처리 결과에 따라 달라진다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "headerName": "X-XSRF-TOKEN",
    "token": "<csrf-token>"
  }
}
```

`message`는 예시 문구이며 실제 문구는 해당 Controller가 지정한다.

## 요청·응답 필드

아래 타입은 코드의 DTO 구성요소다. 응답 DTO는 공통 envelope의 `data` 안에 들어간다. Java `Long`/`Integer`/`int`는 JSON number, enum은 JSON string, `List`는 JSON array다. 시간과 null 처리의 의미는 [공통 규칙](common.md)을 따른다.

<a id="schema-csrfcontroller-csrfresponse"></a>

### CsrfController.CsrfResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `headerName` | `String` | — | CSRF 요청 헤더 이름 |
| `token` | `String` | — | CSRF 요청 토큰 |

근거: [CsrfController.java](../../src/main/java/com/example/attractionreservation/domain/auth/controller/CsrfController.java).

<a id="schema-createauthloginrequest"></a>

### CreateAuthLoginRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `email` | `String` | 필수; 빈 문자열·공백 불가; 이메일 형식 | 이메일 |
| `password` | `String` | 필수; 빈 문자열·공백 불가 | 비밀번호 |

근거: [CreateAuthLoginRequest.java](../../src/main/java/com/example/attractionreservation/domain/auth/dto/CreateAuthLoginRequest.java).

<a id="schema-createauthloginresponse"></a>

### CreateAuthLoginResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `accessToken` | `String` | — | Access Token |
| `tokenType` | `String` | — | Bearer |
| `expiresIn` | `Long` | — | 유효 시간(초) |

근거: [CreateAuthLoginResponse.java](../../src/main/java/com/example/attractionreservation/domain/auth/dto/CreateAuthLoginResponse.java).

<a id="schema-createsocialloginexchangerequest"></a>

### CreateSocialLoginExchangeRequest

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `code` | `String` | 필수; 빈 문자열·공백 불가 | 서버 발급 일회용 로그인 코드 |

근거: [CreateSocialLoginExchangeRequest.java](../../src/main/java/com/example/attractionreservation/domain/auth/dto/CreateSocialLoginExchangeRequest.java).

<a id="schema-createauthrefreshresponse"></a>

### CreateAuthRefreshResponse

| 필드 | 타입 | 검증·기본값 | 설명 |
|---|---|---|---|
| `accessToken` | `String` | — | Access Token |
| `tokenType` | `String` | — | Bearer |
| `expiresIn` | `Long` | — | 유효 시간(초) |

근거: [CreateAuthRefreshResponse.java](../../src/main/java/com/example/attractionreservation/domain/auth/dto/CreateAuthRefreshResponse.java).

## 주요 오류

모든 endpoint에는 [공통 인증·입력 오류](common.md)가 추가로 발생할 수 있다. 아래 이름은 서버 내부 ErrorCode이며 JSON에 `errorCode` 필드로 반환되지 않는다. 각 행은 도메인의 주요 상황을 모은 것으로 모든 endpoint가 모든 오류를 반환한다는 뜻은 아니다.

| HTTP | 내부 이름 | 기본 메시지 |
|---|---|---|
| 401 | `INVALID_LOGIN_CREDENTIALS` | 이메일 또는 비밀번호가 올바르지 않습니다. |
| 403 | `USER_NOT_ACTIVE` | 이용할 수 없는 회원 상태입니다. |
| 401 | `INVALID_REFRESH_TOKEN` | 유효하지 않거나 만료된 Refresh Token입니다. |
| 401 | `SOCIAL_LOGIN_CODE_INVALID` | 유효하지 않거나 만료되었거나 이미 사용된 로그인 코드입니다. |

## 구현 근거

[AuthController.java](../../src/main/java/com/example/attractionreservation/domain/auth/controller/AuthController.java), [CsrfController.java](../../src/main/java/com/example/attractionreservation/domain/auth/controller/CsrfController.java) 및 해당 Service·Repository·DTO·enum. 공통 인증은 SecurityConfig 기준이다. 작성 과정에서 API 호출이나 테스트를 실행하지 않았다.
