# 테마파크 예약 앱 – 리액트 프론트엔드

캔버스에서 만든 14개 화면(사용자 11 + 관리자 3)을 **리액트 코드**로 옮긴 프로젝트입니다.

- PC 브라우저에서 열면 **휴대폰 화면** 처럼 보여요.
- 실제 휴대폰으로 열면 틀 없이 **화면을 꽉 채워요**.
- 지금은 **가짜 데이터(mock)** 로 돌아가고, 나중에 **스프링부트**를 붙이면 설정 한 줄만 바꾸면 돼요.

---

## 1. 실행하기

준비물: [Node.js](https://nodejs.org) 18 이상 (LTS 버전 추천)

```bash
cd themepark-front
npm install      # 처음 한 번만: 필요한 라이브러리 내려받기
npm run dev      # 개발 서버 실행 → http://localhost:5173
```

테스트 계정 (가짜 데이터일 때만)
- 일반 회원: `test@example.com` / `1234`
- 관리자: `admin@example.com` / `1234`
- 관리자 화면 주소: `http://localhost:5173/admin`

---

## 2. 폴더 구조 (어디에 뭐가 있나요?)

```
themepark-front/
├─ index.html                 # 앱이 그려지는 빈 HTML (폰트 불러오기)
├─ vite.config.js             # 개발 서버 설정 (/api → 스프링부트 8080 으로 전달)
├─ .env.development           # VITE_USE_MOCK=true  → 가짜 데이터 사용
├─ .env.production            # VITE_USE_MOCK=false → 진짜 백엔드 사용
├─ public/images/             # 사진, 로고
├─ spring-guide/              # 스프링부트에 넣을 예시 자바 코드 (아래 4번 참고)
└─ src/
   ├─ main.jsx                # 시작점
   ├─ App.jsx                 # 주소 ↔ 화면 연결표 (라우터)
   ├─ config.js              # 서비스 이름, mock 사용 여부
   ├─ styles.css              # 색상·버튼·휴대폰 틀 등 공통 디자인
   ├─ api/
   │  ├─ http.js              # ★ 모든 요청이 지나가는 문 (mock ↔ 진짜 서버 전환)
   │  ├─ index.js             # ★ 화면에서 쓰는 API 함수 목록 = 백엔드가 만들 주소 목록
   │  ├─ mockServer.js        # 가짜 서버 (백엔드 생기면 안 씀)
   │  ├─ labels.js            # 상태 코드 → 한국어/배지 색, 날짜·가격 표시 함수
   │  └─ ApiError.js
   ├─ hooks/useApi.js         # 화면이 열릴 때 데이터 불러오기 도우미
   ├─ context/AuthContext.jsx # 로그인 정보 저장소
   ├─ components/             # 탭바, 배지, 헤더, 아이콘 같은 공통 부품
   └─ pages/                  # 화면 14개 (admin/ 은 관리자 화면)
```

### 화면 ↔ 주소

| 화면 | 주소 |
|---|---|
| 홈 | `/` |
| 로그인 · 회원가입 | `/login` |
| 어트랙션 목록 / 상세·슬롯 예약 | `/attractions`, `/attractions/:id` |
| 입장권 선택 → 주문·결제 | `/tickets` → `/checkout/:orderId` |
| 루트 추천 조건 → 결과 | `/route` → `/route/result` |
| 내 예약 / 내 입장권 / 마이페이지 | `/my/reservations`, `/my/tickets`, `/my` |
| 관리자 슬롯 / 예약 / 회원 | `/admin/slots`, `/admin/reservations`, `/admin/members` |

---

## 3. 데이터가 흐르는 길 (한 장 요약)

```
화면(pages/Home.jsx)
   └─ useApi(() => attractionApi.list('AVAILABLE'))      ← "이 데이터 줘"
        └─ api/index.js : GET /attractions?filter=AVAILABLE
             └─ api/http.js
                  ├─ USE_MOCK=true  → mockServer.js 가 대답
                  └─ USE_MOCK=false → fetch('/api/attractions?...') → 스프링부트
```

화면 코드는 데이터가 어디서 오는지 몰라요. 그래서 **백엔드를 붙여도 화면 코드는 그대로**입니다.

---

## 4. 스프링부트 연결하기 (3단계)

### ① 스프링부트에 API 만들기
아래 5번 **API 명세** 표의 주소와 JSON 모양 그대로 Controller 를 만드세요.
`spring-guide/AttractionController.java` 에 예시가 있어요. (record 필드 이름 = JSON 키)

에러는 `{"message": "이유"}` 모양으로 돌려주세요 → `spring-guide/GlobalExceptionHandler.java`
그러면 리액트가 그 문장을 화면에 그대로 보여 줘요.

### ② 리액트를 진짜 서버로 바꾸기
`.env.development` 파일에서 한 줄만 바꿔요.
```
VITE_USE_MOCK=false
```
스프링부트(8080)를 먼저 켜고 `npm run dev` 를 다시 실행하면 끝.
(`vite.config.js` 의 proxy 덕분에 개발 중에는 CORS 설정이 없어도 돼요.
 proxy 없이 직접 부르려면 `spring-guide/WebConfig.java` 를 넣으세요.)

### ③ 배포할 때 – 스프링부트 하나로 합치기
```bash
npm run build                       # dist/ 폴더가 만들어져요
# dist/ 안의 파일을 전부 스프링부트 프로젝트의 src/main/resources/static/ 에 복사
```
그리고 `spring-guide/SpaForwardController.java` 를 넣어 주세요.
(없으면 `/my/tickets` 같은 주소에서 새로고침할 때 404 가 나요.)

### 로그인 방식
**세션(쿠키 JSESSIONID)** 방식이라고 가정했어요.
- `POST /api/auth/login` 성공 → 서버가 세션을 만들고 회원 정보를 JSON으로 돌려줌
- 이후 요청은 브라우저가 쿠키를 자동으로 붙여 보냄 (`http.js` 의 `credentials: 'include'`)
- 로그인 안 된 요청엔 **401** 을 주세요 → 화면에 "로그인하러 가기"가 떠요
- JWT 로 바꾸고 싶다면 `http.js` 한 곳에서 `Authorization` 헤더만 추가하면 돼요.

---

## 5. API 명세 (스프링부트가 만들 주소)

> 모든 주소 앞에 `/api` 가 붙어요. 날짜는 `"2026-10-24"`, 시간은 `"14:00"` 문자열.
> 응답 예시의 값들은 가짜 데이터예요. 정확한 필드는 `src/api/mockServer.js` 에서 확인할 수 있어요.

### 회원
| 메서드 | 주소 | 보내는 값 | 받는 값 |
|---|---|---|---|
| POST | `/auth/login` | `{email, password}` | `Member` |
| POST | `/auth/signup` | `{nickname, email, password}` | `Member` |
| POST | `/auth/logout` | – | 204 |
| GET | `/members/me` | – | `Member` (비로그인 401) |
| PATCH | `/members/me` | `{nickname}` | `Member` |

`Member = {id, nickname, email, role: USER|ADMIN, status: ACTIVE|INACTIVE|SUSPENDED|WITHDRAWN, joinedAt}`

### 파크 · 어트랙션
| 메서드 | 주소 | 받는 값 |
|---|---|---|
| GET | `/park/today` | `{date, now, openTime, closeTime, operatingCount, maintenanceCount}` |
| GET | `/park/sale-dates` | `[{date, available}]` 입장권 판매 날짜 7일 |
| GET | `/attractions?filter=ALL\|AVAILABLE\|HEIGHT` | `[AttractionSummary]` |
| GET | `/attractions/{id}` | `AttractionDetail` |
| GET | `/attractions/{id}/slots?date=` | `[Slot]` |

`Slot = {id, startTime, capacity, remaining, status: OPEN|CLOSED, bookable, reason: null|PAST|FULL|CLOSED|MINE|CONFLICT}`

### 예약
| 메서드 | 주소 | 보내는 값 | 받는 값 |
|---|---|---|---|
| GET | `/reservations/me?scope=UPCOMING\|ALL` | – | `[Reservation]` |
| POST | `/reservations` | `{slotId, ticketId}` | `Reservation` |
| DELETE | `/reservations/{id}` | – | 204 |

`Reservation = {id, status: RESERVED|USED|CANCELED|NO_SHOW, date, time, attractionId, attractionName, image, zone, rideMinutes, ticketId, ticketType, ...}`

서버에서 꼭 확인할 것: 정원 남았는지, 이용 30분 전인지, 같은 시간에 다른 예약이 있는지, 오후권이면 15:00 이후인지.
(동시에 여러 명이 마지막 자리를 누르는 경우를 생각해서 **잔여석 감소는 트랜잭션/락**으로 처리하는 게 좋아요.)

### 입장권 · 주문
| 메서드 | 주소 | 보내는 값 | 받는 값 |
|---|---|---|---|
| GET | `/ticket-products` | – | `[{id, type: FULL_DAY\|AFTERNOON, name, hours, entryFrom, price}]` |
| GET | `/tickets/me?scope=ALL\|USABLE\|PAST` | – | `[{id, type, date, status: UNUSED\|IN_USE\|USED\|REFUNDED\|EXPIRED, orderId, ownerNickname}]` |
| POST | `/tickets/{id}/refund` | – | `Ticket` |
| POST | `/orders` | `{date, productId, quantity}` | `Order` (status: PENDING) |
| GET | `/orders/{id}` | – | `Order` |
| POST | `/orders/{id}/pay` | `{method: CARD\|EASY_PAY\|BANK_TRANSFER\|PG}` | `Order` (PAID, 입장권 발급) |
| GET | `/orders/me` | – | `[Order]` |

`Order = {id, date, productName, quantity, unitPrice, totalPrice, status: PENDING|PAID|PARTIAL_REFUNDED|REFUNDED, paidAt, refundedQuantity}`

### 루트 추천
| 메서드 | 주소 | 보내는 값 | 받는 값 |
|---|---|---|---|
| POST | `/routes/recommend` | `{ticketId, startTime, endTime, attractionIds: [1,2,3]}` | `{stops: [{order, attractionId, name, image, zone, rideMinutes, slotId, time, remaining}], totalRideMinutes, totalWalkMinutes, walkMinutesBetween, unavailable: [이름]}` |

### 관리자 (ADMIN 만 – 아니면 403)
| 메서드 | 주소 | 보내는 값 |
|---|---|---|
| GET | `/admin/attractions` | – |
| GET | `/admin/attractions/{id}/slots?date=` | – |
| POST | `/admin/attractions/{id}/slots` | `{date, startTime, capacity}` |
| POST | `/admin/attractions/{id}/slots/generate` | `{date}` → `{created}` |
| PATCH | `/admin/slots/{id}` | `{status: OPEN\|CLOSED}` |
| DELETE | `/admin/slots/{id}` | – (예약 있으면 409) |
| GET | `/admin/reservations?q=&status=` | – |
| PATCH | `/admin/reservations/{id}` | `{status: RESERVED\|USED\|NO_SHOW}` |
| POST | `/admin/reservations/{id}/cancel` | – |
| GET | `/admin/members?q=&status=&role=` | – (`ticketCount`, `reservationCount` 포함) |
| PATCH | `/admin/members/{id}` | `{status}` |

---

## 6. 아직 안 만든 것 / 바꿔야 할 것

- **가격**: `mockServer.js` 의 50,000원 / 40,000원은 예시예요.
- **서비스 이름**: `src/config.js` 의 `SERVICE_NAME` 한 줄만 바꾸면 돼요.
- **관리자 접근 막기**: 지금은 누구나 `/admin` 에 들어갈 수 있어요. 서버에서 403 을 주고, 필요하면 프론트에도 막는 코드를 추가하세요.
- **결제**: 실제 PG(토스페이먼츠 등) 연동은 안 되어 있어요. `Checkout.jsx` 의 `pay()` 에 주석으로 위치를 표시해 두었어요.
- **입장 QR**: 자리만 있어요.
- **관리자 – 입장권 상품, 주문·환불** 화면, 비밀번호 변경, 회원 탈퇴, 어트랙션 등록 화면은 와이어프레임에 없어서 버튼만 있어요.
- **사진**: `public/images/` 의 어트랙션 사진은 전달받은 이미지예요. 공개 배포(포트폴리오 등) 전에 사용 권한을 꼭 확인하세요.
- **폰트**: 구글 폰트(Jua, Noto Sans KR)를 인터넷에서 불러와요. 인터넷이 안 되면 기본 글꼴로 보여요.
