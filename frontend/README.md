# 테마파크 예약 앱 – 리액트 프론트엔드

놀이공원 어트랙션 예약 · 최적 루트 추천 서비스의 **화면(프론트엔드)** 입니다.
같은 저장소의 **스프링부트 백엔드**(`/api/...`)와 실제로 통신해요.

- PC 브라우저에서 열면 **휴대폰 화면** 처럼 보여요.
- 실제 휴대폰으로 열면 틀 없이 **화면을 꽉 채워요**.
- 로그인은 **JWT**(Access Token + Refresh Token 쿠키), 결제는 **포트원(PortOne) V2** 를 써요.

| 기술 | 버전 (`package.json` 기준) |
|---|---|
| React / React DOM | ^18.3.1 |
| React Router | ^6.26.2 |
| Vite | ^5.4.8 |
| @portone/browser-sdk | ^0.1.13 |

---

## 1. 실행하기

준비물
- [Node.js](https://nodejs.org) 18 이상 (LTS 버전 추천)
- **스프링부트 백엔드가 `localhost:8080` 에서 켜져 있어야 해요.** (가짜 데이터가 없어서 백엔드 없이는 화면에 데이터가 안 나와요)

```bash
cd frontend
npm install      # 처음 한 번만: 필요한 라이브러리 내려받기
npm run dev      # 개발 서버 실행 → http://localhost:5173
```

| 명령어 | 하는 일 |
|---|---|
| `npm run dev` | 개발 서버 실행 (코드 고치면 바로 반영) |
| `npm run build` | 배포용 파일을 `dist/` 폴더에 만들기 |
| `npm run preview` | 만들어진 `dist/` 를 미리 띄워 보기 |

### 계정
- **일반 회원**: 로그인 화면에서 회원가입하면 돼요. (카카오 · 구글 소셜 로그인도 있어요 → 3번 참고)
- **관리자**: `role = ADMIN` 인 계정으로 로그인한 뒤 `http://localhost:5173/admin` 으로 들어가요.
  관리자 계정을 만드는 방법은 백엔드 쪽 문서를 확인하세요.

---

## 2. 폴더 구조 (어디에 뭐가 있나요?)

```
frontend/
├─ index.html                 # 앱이 그려지는 빈 HTML (폰트 불러오기)
├─ vite.config.js             # 개발 서버 설정 (/api → 스프링부트 8080 으로 전달)
├─ .env.development           # 개발용 설정 (소셜 로그인 백엔드 주소)
├─ .env.production            # 배포용 설정
├─ portone-check.http         # 포트원 설정 확인용 HTTP 요청 모음 (IntelliJ HTTP Client)
├─ public/images/             # 어트랙션 사진, 로고
├─ spring-guide/
│  └─ SpaForwardController.java  # 스프링부트로 같이 배포할 때 필요한 예시 코드 (6번 참고)
└─ src/
   ├─ main.jsx                # 시작점
   ├─ App.jsx                 # 주소 ↔ 화면 연결표 (라우터)
   ├─ config.js               # 서비스 이름, API·소셜 로그인 주소
   ├─ styles.css              # 색상·버튼·휴대폰 틀 등 공통 디자인
   ├─ api/
   │  ├─ http.js              # ★ 모든 요청이 지나가는 문 (토큰·CSRF·재발급·응답 포장 벗기기)
   │  ├─ index.js             # ★ 화면에서 쓰는 API 함수 + 백엔드 응답 → 화면 모양 변환(toXxx)
   │  ├─ labels.js            # 상태 코드 → 한국어/배지 색, 날짜·가격 표시 함수
   │  └─ ApiError.js
   ├─ hooks/useApi.js         # 화면이 열릴 때 데이터 불러오기 도우미
   ├─ context/AuthContext.jsx # 로그인 정보 저장소 (로그인·소셜 로그인·로그아웃·탈퇴)
   ├─ components/
   │  ├─ DeviceFrame.jsx      # PC 에서 보이는 휴대폰 틀
   │  ├─ RequireAdmin.jsx     # 관리자 화면 문지기
   │  ├─ PasswordChangeSheet.jsx  # 비밀번호 변경 창
   │  ├─ WithdrawSheet.jsx    # 회원 탈퇴 창
   │  ├─ ui.jsx               # 탭바, 배지, 헤더 같은 공통 부품
   │  └─ Icon.jsx
   └─ pages/                  # 사용자 화면 (admin/ 은 관리자 화면)
```

### 화면 ↔ 주소

**사용자 화면**

| 화면 | 주소 |
|---|---|
| 홈 | `/` |
| 로그인 · 회원가입 | `/login` |
| 소셜 로그인 도착 | `/oauth/callback` |
| 어트랙션 목록 / 상세·슬롯 예약 | `/attractions`, `/attractions/:id` |
| 입장권 선택 → 주문·결제 → 결제 완료 | `/tickets` → `/checkout/:orderId` → `/checkout/:orderId/complete` |
| 루트 추천 조건 → 결과 | `/route` → `/route/result` |
| 내 예약 / 내 입장권 / 마이페이지 | `/my/reservations`, `/my/tickets`, `/my` |

**관리자 화면** (`RequireAdmin` 이 지켜요 → 비로그인은 로그인 화면으로, 일반 회원은 "권한 없음" 안내)

| 화면 | 주소 |
|---|---|
| 어트랙션 · 슬롯 관리 (`/admin` 으로 오면 여기로 이동) | `/admin/slots` |
| 어트랙션 등록 / 수정 | `/admin/attractions/new`, `/admin/attractions/:id/edit` |
| 예약 관리 | `/admin/reservations` |
| 회원 관리 | `/admin/members` |
| 입장권 상품 관리 | `/admin/products` |
| 주문 · 환불 관리 | `/admin/orders` |
| 입장 처리 (입장권 코드 → 입장 완료) | `/admin/entry` |

없는 주소로 들어오면 홈(`/`)으로 돌아가요.

---

## 3. 환경 설정

### 프론트 `.env`

| 파일 | 값 | 설명 |
|---|---|---|
| `.env.development` | `VITE_OAUTH_BASE_URL=http://localhost:8080` | 소셜 로그인 버튼이 이동할 백엔드 주소 |
| `.env.production` | `VITE_OAUTH_BASE_URL=` (빈 값) | 프론트와 백엔드가 같은 주소로 배포되면 비워 둬요. 다르면 백엔드 주소를 적어요. |

> 왜 소셜 로그인만 따로 주소를 적나요?
> 일반 API 는 `fetch` 요청이라 `vite.config.js` 의 proxy 를 타고 8080 으로 가요.
> 하지만 소셜 로그인은 **브라우저가 페이지를 통째로 이동**하는 방식이라 proxy 를 안 타요. 그래서 백엔드 주소를 직접 적어요.

개인 설정은 `.env.local` 에 적으세요. (`.gitignore` 에 들어 있어서 깃에 안 올라가요)

### 백엔드 쪽에서 프론트와 맞춰야 하는 값

| 백엔드 환경 변수 | 개발 때 예시 값 | 이유 |
|---|---|---|
| `OAUTH2_SUCCESS_REDIRECT_URI` | `http://localhost:5173/oauth/callback` | 소셜 로그인 성공 → `?code=일회용코드` 를 붙여서 이 주소로 보내 줘요 |
| `OAUTH2_FAILURE_REDIRECT_URI` | `http://localhost:5173/oauth/callback` | 소셜 로그인 실패 → `?error=실패코드` 를 붙여서 이 주소로 보내 줘요 |
| `PORTONE_STORE_ID`, `PORTONE_CHANNEL_KEY` 등 | (포트원 콘솔 값) | 결제창 정보는 프론트에 직접 적지 않고 `GET /api/config/portone` 으로 받아 와요 |

---

## 4. 데이터가 흐르는 길 (한 장 요약)

```
화면(pages/Home.jsx)
   └─ useApi(() => attractionApi.list('AVAILABLE'))       ← "이 데이터 줘"
        └─ api/index.js : GET /attractions?size=50&sort=NAME&reservable=true
             └─ api/http.js : fetch('/api/attractions?...')  → 스프링부트
                  ← { success, message, data }  → data 만 꺼내서 돌려줌
        └─ toAttractionSummary() 로 "화면이 쓰는 모양"으로 바꿈
```

화면 코드는 백엔드 응답 모양을 몰라요. 백엔드 응답이 바뀌면 **`api/index.js` 의 toXxx 함수만** 고치면 돼요.

### `http.js` 가 대신 해 주는 일

1. **응답 포장 벗기기**: 서버는 `{ success, message, data }` 로 보내요 → `data` 만 돌려줘요.
2. **출입증 붙이기**: 로그인 때 받은 Access Token 을 `Authorization: Bearer 토큰` 으로 붙여요.
    - Access Token 은 **메모리(변수)** 에만 둬요. 새로고침하면 사라지지만, 앱이 켜질 때 Refresh Token 쿠키로 다시 받아요.
3. **CSRF 도장**: `/api/auth/**` 와 `/api/users/signup` 에 GET 이 아닌 요청을 보낼 때는
   먼저 `GET /api/auth/csrf` 로 도장을 받아 머리글에 같이 보내요.
4. **401 이 오면 한 번만 재시도**: `POST /api/auth/refresh` 로 새 출입증을 받고 같은 요청을 다시 보내요.
5. **에러 문장**: 서버가 준 `message`(입력값 오류면 `data` 의 첫 번째 이유)를 화면에 그대로 보여 줘요.

### 소셜 로그인 흐름 (카카오 · 구글)

```
[카카오로 시작] 클릭
  → (백엔드) /oauth2/authorization/kakao
  → 카카오 로그인 → 백엔드가 회원 확인/가입
  → /oauth/callback?code=일회용코드
  → POST /api/auth/oauth2/exchange { code } → Access Token 받기 → 내 정보 불러오기
```
버튼 목록은 `pages/Login.jsx` 에 있어요. id 는 백엔드 `application-oauth.yml` 의 registrationId 와 같아야 해요.

### 결제 흐름 (포트원 V2)

```
입장권 선택 → POST /api/orders              (주문 생성, 응답에 pgId)
  → GET /api/config/portone                 (storeId, channelKey 받기)
  → PortOne.requestPayment({ paymentId: pgId, ... })   (결제창)
  → POST /api/payments/{pgId}/confirm       (서버가 포트원에 금액을 다시 확인하고 주문 확정)
```
- PC 는 보통 팝업 결제라 `Checkout.jsx` 에서 바로 승인 요청까지 해요.
- 모바일은 결제사 페이지로 이동했다가 `/checkout/:orderId/complete` 로 돌아와서 `PaymentComplete.jsx` 가 승인 요청을 해요.

---

## 5. 프론트가 부르는 API 목록

> 모든 주소 앞에 `/api` 가 붙어요.
> 정확한 요청·응답 필드는 **백엔드 API 명세**와 `src/api/index.js` 의 주석 · toXxx 함수를 기준으로 보세요.

### 인증 · 회원
| 메서드 | 주소 | 쓰는 곳 |
|---|---|---|
| GET | `/auth/csrf` | CSRF 도장 받기 (`http.js` 가 자동으로) |
| POST | `/auth/login` | 로그인 |
| POST | `/auth/refresh` | 출입증 재발급 (`http.js` 가 자동으로) |
| POST | `/auth/logout` | 로그아웃 |
| POST | `/auth/oauth2/exchange` | 소셜 로그인 일회용 코드 → 출입증 |
| POST | `/users/signup` | 회원가입 (가입 후 바로 로그인) |
| GET / PATCH / DELETE | `/users/me` | 내 정보 / 닉네임 수정 / 탈퇴 |
| PATCH | `/users/me/password` | 비밀번호 변경 |

### 어트랙션 · 슬롯 · 예약 · 루트
| 메서드 | 주소 | 쓰는 곳 |
|---|---|---|
| GET | `/attractions` | 어트랙션 목록 (홈의 운영 현황도 이걸로 계산) |
| GET | `/attractions/{id}` | 어트랙션 상세 |
| GET | `/attractions/{id}/slots?date=` | 슬롯 목록 (로그인 필요) |
| GET | `/reservations/me` | 내 예약 |
| POST | `/reservations` | 예약하기 `{ticketId, slotId}` |
| PATCH | `/reservations/{id}/cancel` | 예약 취소 |
| POST | `/route-recommendations` | 루트 추천 `{ticketId, startAt, endAt, attractionIds}` (최대 5개) |

### 입장권 · 주문 · 결제
| 메서드 | 주소 | 쓰는 곳 |
|---|---|---|
| GET | `/ticket-products` | 입장권 상품 목록 |
| GET | `/tickets/me` | 내 입장권 |
| POST | `/orders` | 주문 생성 `{visitDate, items: [{ticketProductId, quantity}]}` |
| GET | `/orders/{id}` | 주문 상세 (결제 화면) |
| GET | `/orders/me` | 내 결제 내역 |
| GET | `/config/portone` | 포트원 상점 정보 |
| POST | `/payments/{pgId}/confirm` | 결제 승인 |
| POST | `/payments/{pgId}/refunds/partial` | 내 입장권 한 장 환불 |

### 관리자 (ADMIN 만)
| 메서드 | 주소 | 쓰는 곳 |
|---|---|---|
| POST / PATCH / DELETE | `/admin/attractions`, `/admin/attractions/{id}` | 어트랙션 등록 / 수정 / 삭제 |
| PATCH | `/admin/attractions/{id}/status` | 어트랙션 운영 상태 변경 |
| GET / POST | `/admin/attractions/{id}/slots` | 슬롯 조회 / 추가 |
| PATCH | `/admin/slots/{id}/status`, `/time`, `/capacity` | 슬롯 상태 / 시간 / 정원 변경 |
| DELETE | `/admin/slots/{id}` | 슬롯 삭제 |
| GET | `/admin/reservations` | 예약 목록 |
| PATCH | `/admin/reservations/{id}/status`, `/cancel` | 예약 상태 변경 / 취소 |
| GET | `/admin/users` | 회원 목록 |
| PATCH | `/admin/users/{id}/status` | 회원 상태 변경 |
| POST / PUT / DELETE | `/admin/ticket-products`, `/admin/ticket-products/{id}` | 입장권 상품 등록 / 수정 / 삭제 |
| GET | `/admin/orders`, `/admin/orders/{id}` | 주문 목록 / 상세 |
| POST | `/admin/orders/{id}/cancel` | 결제 대기 주문 취소 |
| POST | `/admin/payments/{pgId}/refunds`, `/refunds/partial` | 전체 환불 / 부분 환불 |
| POST | `/admin/tickets/entry` | 입장 처리 `{ticketCode}` |

### 백엔드 이름 ↔ 화면 이름 (헷갈리기 쉬운 것)

| 구분 | 백엔드 | 화면 (`labels.js`) |
|---|---|---|
| 입장권 종류 | `ALL_DAY` | `FULL_DAY` |
| 예약 상태 | `COMPLETED` / `CANCELLED` | `USED` / `CANCELED` |

바꾸는 표는 `api/index.js` 의 `TICKET_TYPE_MAP`, `RESERVATION_STATUS_MAP` 에 있어요.

---

## 6. 배포할 때 – 스프링부트 하나로 합치기

```bash
npm run build                       # dist/ 폴더가 만들어져요
# dist/ 안의 파일을 전부 스프링부트 프로젝트의 src/main/resources/static/ 에 복사
```

그리고 `spring-guide/SpaForwardController.java` 를 백엔드에 넣어 주세요.
(없으면 `/my/tickets` 같은 주소에서 새로고침할 때 404 가 나요.)

> ⚠️ 이 파일의 `package` 는 `com.example.themepark.config` 로 되어 있어요.
> 우리 백엔드 패키지(`com.example.attractionreservation...`)에 맞게 바꿔서 넣어야 해요.
> 지금은 백엔드에 아직 들어가 있지 않아요.

같은 주소로 배포하면 `.env.production` 의 `VITE_OAUTH_BASE_URL` 은 빈 값 그대로 두면 돼요.

---

## 7. 알아 두면 좋은 것 / 아직 남은 일

**백엔드에 정보·API 가 없어서 프론트가 대신 처리하는 것**
- **어트랙션 사진**: 백엔드에 사진 정보가 없어서, 이름이 같으면 `public/images/` 사진을 쓰고 없으면 공원 사진을 써요. (`IMAGE_BY_NAME`)
- **키 제한**: 백엔드에 정보가 없어서 "키 제한" 필터는 지금 항상 빈 목록이에요.
- **홈 운영 현황**: 전용 API 가 없어서 어트랙션 목록으로 계산해요. 개장 10:00 / 폐장 22:00 은 프론트에 적혀 있어요.
- **입장권 판매 날짜**: 전용 API 가 없어서 "오늘부터 7일"을 프론트에서 만들어요.
- **슬롯의 "내 예약 / 시간 겹침" 표시**: 내 예약 목록과 비교해서 프론트가 붙여요.
- **슬롯 일괄 생성**: 일괄 생성 API 가 없어서 10:00~21:30 을 30분 간격으로 슬롯 추가 API 를 한 번씩 불러요. (정원 20명 고정)
- **관리자 예약·회원 검색**: 백엔드에 글자 검색이 없어서 최대 100건을 받아 프론트에서 걸러요. 데이터가 많아지면 백엔드 검색 API 가 필요해요.
- **관리자 회원 목록의 입장권 수 / 예약 수**: 응답에 없어서 비어 있어요.

**그 밖에**
- **서비스 이름**: `src/config.js` 의 `SERVICE_NAME` 이 아직 `(서비스 이름)` 이에요. 한 줄만 바꾸면 돼요.
- **결제 수단**: 지금은 카드만 열려 있어요. 간편결제·계좌이체는 `labels.js` 의 `PAYMENT_METHODS` 에 주석으로 막혀 있어요. (테스트 채널이 지원하는지 먼저 확인)
- **입장 QR**: 아직 없어요. 입장권 `code` 값으로 그리면 돼요. (`MyTickets.jsx` 주석 참고)
- **관리자 화면 보호**: `RequireAdmin` 은 "보기 좋게" 막는 것이고, 진짜 보안은 백엔드(`/api/admin/**` → ADMIN 권한)가 해요.
- **사진 권한**: `public/images/` 의 어트랙션 사진은 전달받은 이미지예요. 공개 배포(포트폴리오 등) 전에 사용 권한을 꼭 확인하세요.
- **폰트**: 구글 폰트(Jua, Noto Sans KR)를 인터넷에서 불러와요. 인터넷이 안 되면 기본 글꼴로 보여요.
- **`portone-check.http`**: 계정 비밀번호는 진짜 값을 적어서 커밋하지 마세요. 필요하면 IntelliJ 의 `http-client.private.env.json` 같은 개인 파일로 빼세요.