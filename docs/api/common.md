# API 공통 규칙

[전체 API 목록](README.md)

## 적용 기준

이 명세서는 2026-11-12 로컬 `dev` 기반 코드(`a737e62f`)에서 분기한 `docs/api-specification`의 HTTP 계약을 정리한다. 운영 배포가 이 코드와 동일한지는 확인하지 않았다. Controller, 요청·응답 DTO, Service·Repository, enum, SecurityConfig와 GlobalExceptionHandler를 기준으로 작성했다. 서버를 실행하거나 실제 HTTP 응답을 캡처한 문서가 아니다.

도메인 문서의 응답 타입은 공통 응답의 `data`를 뜻한다. 코드에서 제공하지 않는 기능·필드·정렬을 문서만으로 추가하지 않는다.

## 요청 형식과 식별자

- JSON 본문이 있는 요청: `Content-Type: application/json`. 응답 기본 형식도 JSON이다.
- Path 변수는 필수다. `{orderId}` 같은 숫자 ID와 `{pgId}` 문자열 식별자를 구분한다.
- 금액은 원 단위 정수다. 클라이언트가 보내는 결제 금액 대신 서버의 주문 금액으로 검증한다.
- enum은 대소문자를 구분하는 코드 이름을 사용한다. 한글 설명을 요청 값으로 보내지 않는다.
- 선택 Query는 보내지 않으면 필터를 적용하지 않거나 endpoint의 기본값을 쓴다. 문자열 `"null"`을 null 대신 보내지 않는다.
- DTO의 검증 표는 Bean Validation 선언을 설명한다. 권한·소유권·업무 상태 검사는 별도로 적용된다.

## 인증과 권한

```http
Authorization: Bearer <access-token>
```

| 구분 | 접근 조건 |
|---|---|
| 공개 | 회원가입, 로그인, Refresh Token 재발급, 소셜 코드 교환, CSRF 토큰 조회, 어트랙션 목록·상세 |
| 로그인 | 그 외 일반 API. 현재 티켓 상품 조회·슬롯 조회·어트랙션 질문·PortOne 설정 조회도 포함 |
| ADMIN | `/api/admin/**` 전체 |
| PG 서명 | POST `/api/payments/webhooks/portone`; 사용자 로그인 대신 서명 검증 |
| OAuth2 | `oauth` 프로필의 로그인 시작·callback은 별도 filter chain |

JwtAuthenticationFilter는 Access Token 서명·만료·ACCESS 유형을 검사하고 DB 회원의 ACTIVE 상태 및 현재 권한을 확인한다. Controller의 인증 주체는 `Long` 회원 ID다. 요청 본문의 임의 userId로 본인을 바꾸지 않는다.

미인증·유효하지 않은 토큰은 일반적으로 **401 UNAUTHORIZED**, 관리자 권한 부족은 **403 ACCESS_DENIED**다. 현재 인증 entry point는 잘못된 Access Token도 UNAUTHORIZED로 응답하므로 INVALID_ACCESS_TOKEN 이름을 클라이언트 계약으로 가정하지 않는다. 공개 API라도 불필요하게 잘못된 Bearer Token을 붙이면 JWT filter에서 거부될 수 있다.

### Refresh Token 쿠키

로그인·소셜 코드 교환 성공 시 다음 쿠키를 설정한다.

| 속성 | 값 |
|---|---|
| 이름 | `refreshToken` |
| HttpOnly | true |
| Secure | true |
| SameSite | Lax |
| Path | `/api/auth` |
| Max-Age | Refresh Token 설정 유효기간(기본 7일) |

JavaScript로 쿠키 값을 읽는 대신 브라우저가 요청에 쿠키를 전송하도록 한다. 배포 origin·HTTPS·SameSite 조건을 충족해야 하며 cross-origin 호출은 credentials와 서버의 origin 정책도 함께 맞아야 한다. 로컬 HTTP에서도 Secure 쿠키가 항상 전달된다고 가정하지 않는다.

재발급은 Access Token만 새로 만들며 Refresh Token을 회전하거나 유효기간을 연장하지 않는다. 로그아웃은 Refresh Token을 무효화하고 쿠키를 지운다. Access Token 자체를 blacklist에 넣는 동작과는 구분한다.

### CSRF 호출 순서

`/api/auth/**`와 `/api/users/signup`의 안전하지 않은 HTTP 메서드에는 CSRF 검증을 적용한다. `permitAll`은 CSRF 면제를 뜻하지 않는다.

1. GET `/api/auth/csrf`로 토큰을 받고 검증 쿠키도 보관한다.
2. 응답의 `data.headerName` 이름으로 `data.token` 값을 요청 헤더에 넣는다.
3. 로그인·회원가입·소셜 코드 교환·재발급·로그아웃을 호출할 때 쿠키와 헤더를 함께 전송한다.

검증 쿠키 이름은 기본 `XSRF-TOKEN`, 헤더 이름은 기본 `X-XSRF-TOKEN`이다. 코드에서 HttpOnly=true, Secure=true, SameSite=Lax, Path=/로 설정하므로 쿠키를 JavaScript에서 직접 읽지 않고 CSRF API의 본문 값을 사용한다. 응답 토큰은 검증 쿠키 원문과 같다고 가정하지 않는다. CSRF 누락·불일치는 403이다. 다른 도메인 API 전체에 CSRF 헤더가 필수인 것은 아니다.

## 공통 응답

데이터 있는 성공:

```json
{
  "success": true,
  "message": "처리 결과 메시지",
  "data": {"id": 1}
}
```

데이터 없는 성공:

```json
{
  "success": true,
  "message": "처리 결과 메시지"
}
```

일반 업무·인증 오류:

```json
{
  "success": false,
  "message": "인증이 필요합니다."
}
```

본문 필드 검증 오류(400)의 예:

```json
{
  "success": false,
  "message": "입력값을 확인해주세요",
  "data": {"ticketId": "널이어서는 안됩니다"}
}
```

- ApiResponse에 `@JsonInclude(NON_NULL)`이 있어 envelope의 data=null은 **키 자체를 생략**한다. 중첩 DTO의 null 필드까지 일괄 생략하는 설정이라는 뜻은 아니다.
- 별도 `errorCode` 필드는 없다. 문서의 오류 이름은 서버 내부 식별자다. 프론트가 response.errorCode를 읽는 구현을 하면 안 된다.
- 필드별 검증 메시지는 annotation·locale에 따라 달라질 수 있다. 모든 400에서 data에 필드 목록이 담기는 것은 아니다.
- 업무 예외에 사용자 지정 메시지가 있으면 ErrorCode 기본 메시지보다 우선한다.
- 삭제·로그아웃 등 데이터 없는 성공도 현재 Controller는 200과 envelope를 반환한다. 204로 가정하지 않는다.

<a id="pagination"></a>

## pagination

페이지 번호는 **0부터** 시작한다. 응답의 `content`는 현재 페이지의 데이터이며 결과가 없으면 빈 배열이다.

| 도메인 | 요청 기본값 | 응답 페이지 번호 필드 | 정렬 방식 |
|---|---|---|---|
| 티켓 상품 | page=0, size=10 | number | type DESC, id DESC 기본 |
| 티켓 | page=0, size=10 | number | status DESC, id DESC 기본 |
| 예약·관리자 예약 | page=0, size=10 | number | status DESC, id DESC 기본 |
| 결제 | page=0, size=10 | page | createdAt DESC, id DESC 기본 |
| 관리자 회원 | page=0, size=10 | page | createdAt DESC, id DESC 고정 |
| 회원·관리자 주문 | page=0, size=10 | page | createdAt DESC, id DESC |
| 어트랙션 | page=0, size=10(최대50) | page | NAME 또는 LATEST(기본 NAME) |
| 슬롯·추천 가능 어트랙션 | pagination 없음 | 해당 없음 | endpoint 설명 참조 |

Spring Pageable을 받는 티켓 상품·티켓·예약·결제는 `?page=0&size=10&sort=status,desc&sort=id,desc`처럼 요청한다. 단, 정렬 속성은 도메인별 허용 목록을 따른다. 위 status 정렬 예시를 결제에 그대로 적용할 수 없다. 앞 세 도메인은 id 정렬을 지정하지 않으면 id DESC를 추가하고 결제는 Repository가 id DESC를 뒤에 추가한다.

Condition DTO 기반의 회원·주문·어트랙션에 Pageable sort 형식을 공통 적용하지 않는다. 명시된 size 상한은 관리자 주문100·어트랙션50이다. 코드에 없는 전체 API 공통 size 상한을 보장하지 않는다.

결제 응답만 `hasNext`를 추가로 제공한다. 나머지 목록은 `content`, `number` 또는 `page`, `size`, `totalElements`, `totalPages`다.

## 날짜와 시각

| 코드 타입 | JSON 예시 | 의미 |
|---|---|---|
| LocalDate | `2026-11-13` | 방문일·조회 날짜 |
| LocalDateTime | `2026-11-13T14:00:00` | offset 없는 KST 업무 시각 |
| Instant | `2026-11-13T05:00:00Z` | 절대 시각(UTC 표현). 필요 시 프론트에서 KST 표시 |
| 위치 timestamp | `1794546000000` | Unix epoch 밀리초 |

같은 이름의 createdAt이라도 DTO마다 Instant/LocalDateTime이 다르다. 이 명세서의 타입 표를 따른다. 모든 응답이 이미 `+09:00` 문자열로 변환된다고 가정하지 않는다. 소수 초 정밀도는 필드·serializer에 따라 달라질 수 있다.

예시 날짜·ID·코드는 설명용이다. 실제 요청 시 유효한 방문일·입장권·슬롯 값으로 교체한다. 마감 판단은 클라이언트 시계가 아닌 서버 시각이다. 예약·사용자 취소는 시작 30분 전 정각까지, 입장은 방문일 22:00 정각 전까지, 이용 완료·노쇼는 슬롯 종료 정각부터다.

## 공통 오류

| HTTP | 내부 이름 | 상황 |
|---|---|---|
| 400 | INVALID_INPUT_VALUE | Bean Validation 또는 입력 조건 위반 |
| 400 | INVALID_REQUEST | Query·Path 타입 변환 실패 등 |
| 400 | INVALID_REQUEST_BODY | JSON 문법·enum·본문 타입 변환 실패 |
| 400 | MISSING_REQUEST_VALUE | 필수 Query 누락 |
| 401 | UNAUTHORIZED | JWT 없음·유효하지 않음·현재 인증 불가 |
| 403 | ACCESS_DENIED | 관리자 권한 부족·CSRF 검증 실패 |
| 404 | URL_NOT_FOUND | 매핑 없는 경로 |
| 405 | METHOD_NOT_ALLOWED | 지원하지 않는 HTTP method |
| 409 | CONCURRENT_UPDATE_CONFLICT | 비관적 잠금 충돌 또는 명시적 상품 version 충돌 |
| 415 | UNSUPPORTED_MEDIA_TYPE | Content-Type 미지원 |
| 500 | INTERNAL_SERVER_ERROR | 처리하지 못한 예외·미변환 DB 제약 위반 |

인증 filter에서 먼저 거부되면 Controller의 validation·404 처리까지 도달하지 않을 수 있다. 예상하지 못한 DB 제약 오류 전체를 자동으로 409로 변환하는 구현은 아니다. 상태 변경 요청의 실패를 받았다고 무조건 재전송하지 말고 상세 조회·도메인 상태를 확인한다.

## 운영 endpoint

`GET /actuator/health`, `GET /actuator/health/**`, `GET /actuator/prometheus`, `GET /readyz`는 SecurityConfig에서 공개 허용한다. 실제 노출 경로·관리 포트·readiness 추가 경로는 프로필과 actuator 설정에 따른다. 업무용 API의 ApiResponse envelope를 적용하는 Controller가 아니며 외부 공개 API 주소로 간주하지 않는다.

## 문서와 코드의 차이·확인할 정책

| 항목 | 현재 코드 계약 | 참고 자료 또는 확인할 점 |
|---|---|---|
| data 없음 | envelope에서 키 생략 | sources 코드 컨벤션 원본은 data=null 명시. 사용자가 전달한 변경 방침·현재 코드에 맞춰 작성했으며 원본은 수정하지 않음 |
| 시간 응답 | Instant와 KST LocalDateTime 혼용 | BR-COM의 KST 업무 기준과 JSON 직렬화 형식은 구분 필요 |
| 탈퇴 본문 | 비밀번호 없이 DELETE /api/users/me | sources 사용자 시나리오에는 비밀번호 확인 흐름이 남음 |
| 취소 상태 | CANCELLED | sources 일부 표는 CANCELED 표기 |
| 상품 삭제 제외 | 목록은 DELETED 제외, 상세는 ID 조회로 반환 가능 | 상세도 삭제 상품을 숨길지 정책 확인 필요 |
| 환불 티켓 | 요청 단계에서 REFUNDED로 사용 차단 | BR-PAY-14 원본의 환불 완료 시점 전이와 차이. PG 완료 여부는 RefundStatus로 판단 |
| 경로 추천 입력 | 현재 위치+ticketId+선택 어트랙션 | sources는 사용자 시작·종료 시각 입력을 포함. 현재 API는 서버가 현재~폐장으로 설정 |
| 기존 예약과 추천 | 끝나지 않은 RESERVED 예약이 있으면 추천 차단 | sources BR-RTE-31의 30분·부분 추천 설명과 차이 |
| 추천 일괄 예약 | 하나라도 실패하면 전체 롤백 | sources BR-RTE-32에 성공 건 유지/전체 롤백 논의가 남음 |

위 차이는 기존 코드를 바꿔 해결한 것이 아니다. 이 문서는 현재 동작을 명시하며, 팀의 최신 정책 문서가 갱신되면 함께 대조해야 한다.

## 구현 근거

- [SecurityConfig](../../src/main/java/com/example/attractionreservation/global/config/SecurityConfig.java)
- [OAuth2SecurityConfig](../../src/main/java/com/example/attractionreservation/global/config/OAuth2SecurityConfig.java)
- [JwtAuthenticationFilter](../../src/main/java/com/example/attractionreservation/domain/auth/jwt/JwtAuthenticationFilter.java)
- [ApiResponse](../../src/main/java/com/example/attractionreservation/global/response/ApiResponse.java)
- [GlobalExceptionHandler](../../src/main/java/com/example/attractionreservation/global/exception/GlobalExceptionHandler.java)
- [PageableDto](../../src/main/java/com/example/attractionreservation/global/response/PageableDto.java)
