import { http, toQuery } from './http.js';

/*
 * 화면에서 부르는 API 함수 목록입니다.
 *
 * 모든 함수는 진짜 스프링부트 서버(http)를 부르고, 서버 응답을 "화면이 쓰는 모양"으로 바꿔 줘요.
 *
 * 화면(pages/*.jsx) 코드는 이 파일의 함수 이름과 돌려주는 모양만 알면 돼요.
 * 그래서 "모양 바꾸기(toXxx 함수)"를 여기서 해 두면 화면 코드는 고치지 않아도 됩니다.
 */

// ───────── 공통 도우미 ─────────

// "2026-11-02T14:30:00" → "14:30"
const toHHmm = (dateTime) => (dateTime ? dateTime.slice(11, 16) : null);

// 오늘 날짜 "2026-11-02" (브라우저의 현지 시각 기준)
function todayString() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

// 백엔드에는 사진 정보가 없어서, 이름이 같으면 public/images 사진을 쓰고 없으면 공원 사진을 써요.
const IMAGE_BY_NAME = {
  '모니모 러쉬': '/images/monimo-rush.jpg',
  '롤링 엑스 트레인': '/images/rolling-x-train.jpg',
  '아마존 익스프레스': '/images/amazon-express.jpg',
  '썬더폴스': '/images/thunder-falls.jpg',
  '범퍼카': '/images/bumper-car.jpg',
  '우주전투기': '/images/space-fighter.jpg',
  '콜럼버스 대탐험': '/images/columbus.jpg',
  '렛츠 트위스트': '/images/lets-twist.jpg',
  '더블 락스핀': '/images/double-rock-spin.jpg',
  '허리케인': '/images/hurricane.jpg', // AI 생성 이미지
};
const imageOf = (name) => IMAGE_BY_NAME[name] || '/images/park.jpg';

// 화면의 배지 표(labels.js)에 없는 상태(DELETED 등)가 오면 화면이 깨지지 않도록 '일시중단'으로 보여 줘요.
const KNOWN_STATUS = ['OPERATING', 'MAINTENANCE', 'SUSPENDED'];
const safeStatus = (s) => (KNOWN_STATUS.includes(s) ? s : 'SUSPENDED');

// ───────── 모양 바꾸기: 백엔드 응답 → 화면이 쓰던 모양 ─────────

// [목록 한 칸] SearchAttractionListResponse.AttractionItem → 화면 모양
function toAttractionSummary(item) {
  const status = safeStatus(item.operationStatus);
  return {
    id: item.attractionId,
    name: item.name,
    image: imageOf(item.name),
    zone: item.location,
    rideMinutes: item.durationMinutes,
    description: item.description,
    status,
    // nextSlot 은 "오늘 예약 가능한 가장 빠른 슬롯" → 없으면 오늘은 예약 마감
    soldOut: status === 'OPERATING' && !item.nextSlot,
    nextSlot: item.nextSlot
        ? { time: toHHmm(item.nextSlot.startsAt), remaining: item.nextSlot.remainingCount }
        : null,
  };
}

// [상세] SearchAttractionResponse → 화면 모양
function toAttractionDetail(d) {
  return {
    id: d.attractionId,
    name: d.name,
    image: imageOf(d.name),
    zone: d.location,
    rideMinutes: d.durationMinutes,
    description: d.description,
    status: safeStatus(d.operationStatus),
    // 키 정보: 백엔드 값이 없으면(null) 화면에서 "정보 없음"으로 보여 준다
    minHeightCm: d.minHeightCm ?? null,
    maxHeightCm: d.maxHeightCm ?? null,
    guardianHeightCm: d.guardianHeightCm ?? null,
    heightLimited: d.minHeightCm != null || d.maxHeightCm != null,
    soldOut: false,
    nextSlot: null,
  };
}

// 백엔드의 "예약 불가 이유" → 화면이 쓰는 이유 코드
const REASON_MAP = {
  FULL: 'FULL',                       // 잔여 정원 없음 → "마감"
  DEADLINE_PASSED: 'PAST',            // 예약 마감 시각 지남 → "예약 시간 지남"
  ATTRACTION_NOT_OPERATING: 'CLOSED', // 어트랙션 운영 안 함 → "운영 안 함"
  SUSPENDED: 'CLOSED',                // 슬롯 운영 중단 → "운영 안 함"
};

// [슬롯 한 칸] SearchAttractionSlotResponse.SlotItem → 화면 모양
// (백엔드는 "내 예약(MINE)", "다른 예약과 겹침(CONFLICT)"은 알려 주지 않아요)
function toSlot(s) {
  return {
    id: s.slotId,
    startTime: toHHmm(s.startsAt),
    capacity: s.capacity,
    remaining: s.remainingCount,
    status: s.status,
    bookable: Boolean(s.reservable),
    reason: s.reservable ? null : (REASON_MAP[s.unavailableReason] || 'CLOSED'),
  };
}

// 내 예약과 비교해서 슬롯에 '내 예약(MINE)' / '겹침(CONFLICT)' 표시 붙이기
// 겹침 기준: (슬롯 시작 < 예약 끝) 이고 (예약 시작 < 슬롯 끝) → 시간이 조금이라도 겹침
// 백엔드 ReservationService 도 같은 회원의 시간 겹침 예약을 막아요.
function markMine(raw, slot, myReserved) {
  if (myReserved.some((r) => r.slotId === raw.slotId)) {
    return { ...slot, bookable: false, reason: 'MINE' };
  }
  const clash = myReserved.some((r) => raw.startsAt < r.endsAt && r.startsAt < raw.endsAt);
  if (slot.bookable && clash) return { ...slot, bookable: false, reason: 'CONFLICT' };
  return slot;
}

// ───────── 회원 / 로그인 ✅ ─────────
export const authApi = {
  // [POST /api/auth/login] → { accessToken, tokenType, expiresIn }
  login: (email, password) => http.post('/auth/login', { email, password }),
  // [POST /api/auth/oauth2/exchange] { code } → { accessToken, tokenType, expiresIn }
  // code 는 카카오·구글 코드가 아니라 "우리 서버"가 발급한 일회용 로그인 코드예요. 한 번 쓰면 사라져요.
  // /auth/ 로 시작해서 http.js 가 CSRF 도장을 자동으로 붙여 줘요.
  exchangeSocialCode: (code) => http.post('/auth/oauth2/exchange', { code }),
  // [POST /api/users/signup] (가입만 하고 로그인은 따로 해야 해요 → AuthContext 에서 이어서 로그인)
  signup: (nickname, email, password) => http.post('/users/signup', { email, password, nickname }),
  // [POST /api/auth/logout]
  logout: () => http.post('/auth/logout'),
  // [GET /api/users/me] → { id, email, nickname, role, status }
  me: () => http.get('/users/me'),
  // [PATCH /api/users/me] 수정 응답에는 role 이 없어서, 수정 후 내 정보를 다시 불러와요.
  updateNickname: async (nickname) => {
    await http.patch('/users/me', { nickname });
    return http.get('/users/me');
  },
  // [PATCH /api/users/me/password] { currentPassword, newPassword } → 응답 data 는 null
  // 현재 비밀번호가 틀리면 400 "현재 비밀번호가 일치하지 않습니다." 가 와요.
  changePassword: (currentPassword, newPassword) =>
      http.patch('/users/me/password', { currentPassword, newPassword }),
  // [DELETE /api/users/me] 진짜로 지우지 않고 탈퇴(WITHDRAWN) 상태로 바꿔요. (소프트 삭제)
  // 서버가 이용 예정 예약을 자동 취소하고, Refresh Token 쿠키도 지워 줘요.
  // 미사용 입장권이 있으면 409 "미사용 입장권이 있어 탈퇴할 수 없습니다..." 가 와요.
  withdraw: () => http.delete('/users/me'),
};

// ───────── 어트랙션 / 슬롯 ✅ ─────────
export const attractionApi = {
  // [GET /api/attractions?size=50&reservable=true]
  // 화면의 필터 → 백엔드 검색 조건으로 바꿔요.
  list: async (filter = 'ALL') => {
    const params = { size: 50, sort: 'NAME' };
    if (filter === 'AVAILABLE') params.reservable = true; // 오늘 예약 가능한 슬롯이 있는 것만
    const page = await http.get(`/attractions${toQuery(params)}`);
    return page.content.map(toAttractionSummary);
  },
  // [GET /api/attractions?page=&size=&keyword=&sort=&reservable=&operating=]
  // 어트랙션 목록 화면 전용: 검색·정렬·필터·페이징을 모두 "서버에게" 맡겨요.
  //  - keyword : 이름 검색어 (빈 값이면 안 보내요)
  //  - sort    : 'NAME'(이름순) | 'LATEST'(최신순)  ← 백엔드 @Pattern 이 이 두 개만 받아요
  //  - filter  : 'ALL' | 'AVAILABLE'(예약 가능) | 'OPERATING'(운영 중)
  //  - height  : 탑승할 사람의 키(cm). null 이면 키 필터를 쓰지 않아요. (백엔드 허용 범위 50~250)
  // 돌려주는 값: { items, page, totalElements, hasNext }
  search: async ({ page = 0, size = 10, keyword = '', sort = 'NAME', filter = 'ALL', height = null } = {}) => {
    const params = { page, size, sort };
    if (keyword.trim()) params.keyword = keyword.trim();
    if (height != null) params.height = height;
    if (filter === 'AVAILABLE') params.reservable = true;
    if (filter === 'OPERATING') params.operating = true;
    const res = await http.get(`/attractions${toQuery(params)}`);
    return {
      items: res.content.map(toAttractionSummary),
      page: res.page,
      totalElements: res.totalElements,
      // 응답에 hasNext 가 없어서 계산해요. (page 는 0부터 시작)
      hasNext: res.page + 1 < res.totalPages,
    };
  },
  // [GET /api/attractions/{id}]
  detail: async (id) => toAttractionDetail(await http.get(`/attractions/${id}`)),
  // [GET /api/attractions/{id}/slots?date=] (로그인해야 볼 수 있어요)
  slots: async (id, date) => {
    const [res, myReserved] = await Promise.all([
      http.get(`/attractions/${id}/slots${toQuery({ date })}`),
      // 내 "예약 완료" 목록도 같이 받아서 '내 예약' / '다른 예약과 겹침' 표시를 만들어요.
      // (실패해도 슬롯은 보여야 하니까 빈 목록으로 대신해요)
      http.get(`/reservations/me${toQuery({ status: 'RESERVED', size: 50 })}`)
          .then((page) => page.content)
          .catch(() => []),
    ]);
    return res.slots.map((s) => markMine(s, toSlot(s), myReserved));
  },
};

// ───────── 어트랙션 질문 (Q&A) ✅ ─────────
export const attractionQuestionApi = {
  // [POST /api/attractions/questions] { question } → { type, intent, attractionId, attractionName, answer, candidates }
  //  - 로그인 필요, CSRF 도장은 필요 없어요. (http.js 가 알아서 처리)
  //  - 되묻기(NEED_CLARIFICATION)·미지원(UNSUPPORTED)도 정상 처리라 200 이에요.
  //  - 서버에 Jev API 키가 없거나 Jev 가 실패하면 503 이 와요.
  ask: (question) => http.post('/attractions/questions', { question }),
};

// ───────── 파크 정보 ─────────
export const parkApi = {
  // ✅ 백엔드에 전용 API가 없어서 프론트에서 계산해요.
  //    개장/폐장 시각은 AttractionSlot.java 의 PARK_OPEN_TIME / PARK_CLOSE_TIME 과 같아요.
  today: async () => {
    const all = await attractionApi.list('ALL');
    const now = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return {
      date: todayString(),
      now: `${p(now.getHours())}:${p(now.getMinutes())}`,
      openTime: '10:00',
      closeTime: '22:00',
      operatingCount: all.filter((a) => a.status === 'OPERATING').length,
      maintenanceCount: all.filter((a) => a.status !== 'OPERATING').length,
    };
  },
  // ✅ 판매 날짜: 백엔드에 전용 API가 없어서 "오늘부터 7일"을 프론트에서 만들어요.
  //    백엔드 주문 규칙은 오늘 ~ 오늘+29일까지 방문일로 고를 수 있어요 (OrderService.MAX_VISIT_DAYS = 30).
  //    화면 한 줄에 맞게 그중 앞 7일만 보여 줘요.
  saleDates: async () => {
    const start = new Date(`${todayString()}T00:00:00`);
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(start);
      d.setDate(start.getDate() + i);
      const p = (n) => String(n).padStart(2, '0');
      return { date: `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`, available: true };
    });
  },
};

// ───────── 입장권 ✅ (목록·상품·환불 연결) ─────────

// 백엔드 입장권 종류 이름 → 화면이 쓰는 이름 (labels.js 의 TICKET_TYPE)
const TICKET_TYPE_MAP = { ALL_DAY: 'FULL_DAY', AFTERNOON: 'AFTERNOON' };

// [입장권 한 장] SearchTicketResponse → 화면 모양
function toTicket(t) {
  return {
    id: t.ticketId,
    code: t.ticketCode,
    name: t.name,
    type: TICKET_TYPE_MAP[t.type] || t.type,
    date: t.validDate,
    status: t.status,
    orderId: t.orderId,     // 주문번호 (화면 표시용)
    pgId: t.pgId,           // 결제번호 (환불 요청 주소에 들어가요)
    ownerNickname: '본인',  // 내 입장권 목록이라 항상 본인이에요
  };
}

// [상품 한 개] SearchTicketProductResponse → 화면 모양
// 화면(Tickets.jsx)은 상품 이름 아래 작은 글씨로 p.hours 를 보여 줘서, 상품 설명을 그 자리에 넣어요.
// 입장권 종류별 입장 시작 시각 (백엔드 TicketType enum 의 availableFrom 과 같은 값)
//   ALL_DAY(10:00)는 개장 시각과 같아서 따로 안내하지 않아요.
const ENTRY_FROM = { AFTERNOON: '15:00' };

function toProduct(p) {
  return {
    id: p.ticketProductId,
    name: p.name,
    type: TICKET_TYPE_MAP[p.type] || p.type, // ALL_DAY → FULL_DAY (화면이 쓰는 이름)
    price: p.price,
    hours: p.description,
    entryFrom: ENTRY_FROM[p.type] ?? null,   // 위쪽 안내 배너의 "15:00부터 입장" 문구에 써요
    status: p.status,                        // ON_SALE | STOPPED | ENDED | OUT_OF_STOCK
  };
}

const USABLE_TICKET = ['UNUSED', 'IN_USE'];

export const ticketApi = {
  // [GET /api/tickets/me?size=50]
  // 화면의 scope(ALL | USABLE | PAST)는 백엔드에 없어서, 전부 받아온 뒤 프론트에서 걸러요.
  mine: async (scope = 'ALL') => {
    const page = await http.get(`/tickets/me${toQuery({ size: 50 })}`);
    let list = page.content.map(toTicket);
    if (scope === 'USABLE') list = list.filter((t) => USABLE_TICKET.includes(t.status));
    if (scope === 'PAST') list = list.filter((t) => !USABLE_TICKET.includes(t.status));

    // 예약에 바로 쓸 수 있는 "오늘 + 입장 완료(IN_USE)" 입장권을 맨 앞으로 보내요.
    // (상세 화면은 목록의 첫 번째 입장권을 기본으로 골라요)
    const today = todayString();
    const rank = (t) => (t.status === 'IN_USE' && t.date === today ? 0 : 1);
    return list.sort((a, b) => rank(a) - rank(b) || b.date.localeCompare(a.date));
  },
  // [GET /api/ticket-products?size=20] 판매 중인 입장권 상품 목록
  products: async () => {
    const page = await http.get(`/ticket-products${toQuery({ size: 20 })}`);
    return page.content.map(toProduct);
  },
  // [POST /api/payments/{pgId}/refunds/partial] { ticketIds: [입장권번호], reason }
  // 입장권 한 장만 골라서 환불해요. 응답: { refundId, refundAmount, status }
  // status 가 COMPLETED 가 아니면(REQUESTED·PROCESSING 등) 서버가 PG 취소를 이어서 처리하는 중이에요.
  refund: (ticket, reason) =>
      http.post(`/payments/${ticket.pgId}/refunds/partial`, { ticketIds: [ticket.id], reason }),
};

// ───────── 예약 ✅ ─────────

// 백엔드 예약 상태 → 화면이 쓰는 상태 (labels.js 의 RESERVATION_STATUS)
const RESERVATION_STATUS_MAP = {
  RESERVED: 'RESERVED',
  COMPLETED: 'USED',
  CANCELLED: 'CANCELED',
  NO_SHOW: 'NO_SHOW',
};

// 예약 응답에는 어트랙션 id·위치·탑승 시간이 없어서,
// 어트랙션 목록을 한 번 받아 와서 "이름"으로 찾아 붙여요.
async function attractionsByName() {
  const all = await attractionApi.list('ALL');
  return new Map(all.map((a) => [a.name, a]));
}

// [예약 한 건] SearchReservationResponse → 화면 모양
function toReservation(r, byName) {
  const a = byName.get(r.attractionName);
  return {
    id: r.reservationId,                 // 취소할 때 쓰는 번호
    number: r.reservationNumber,         // 사람이 보는 예약번호
    status: RESERVATION_STATUS_MAP[r.status] || r.status,
    date: r.startsAt.slice(0, 10),       // "2026-11-02"
    time: toHHmm(r.startsAt),            // "14:30"
    attractionName: r.attractionName,
    attractionId: a?.id ?? null,
    image: a?.image ?? imageOf(r.attractionName),
    zone: a?.zone ?? '',
    rideMinutes: a?.rideMinutes ?? '-',
    ticketId: r.ticketId,
    slotId: r.slotId,
  };
}

export const reservationApi = {
  // [GET /api/reservations/me?status=RESERVED&size=50]
  // UPCOMING(남은 예약) = 예약 완료 상태만, ALL = 전부
  mine: async (scope = 'UPCOMING') => {
    const params = { size: 50 };
    if (scope === 'UPCOMING') params.status = 'RESERVED';
    const [page, byName] = await Promise.all([
      http.get(`/reservations/me${toQuery(params)}`),
      attractionsByName(),
    ]);
    const list = page.content.map((r) => toReservation(r, byName));
    // 남은 예약은 빠른 시간순, 전체는 최근 날짜순
    return scope === 'UPCOMING'
        ? list.sort((x, y) => (x.date + x.time).localeCompare(y.date + y.time))
        : list.sort((x, y) => (y.date + y.time).localeCompare(x.date + x.time));
  },

  // [POST /api/reservations] { ticketId, slotId }
  // 화면의 <select> 는 값을 글자("3")로 주기 때문에 숫자로 바꿔서 보내요.
  create: async (slotId, ticketId) => {
    const r = await http.post('/reservations', {
      ticketId: Number(ticketId),
      slotId: Number(slotId),
    });
    // 완료 안내에 예약번호를 보여 주려고 id 자리에 reservationNumber 를 넣어요.
    return { id: r.reservationNumber, reservationId: r.reservationId };
  },

  // [PATCH /api/reservations/{id}/cancel]
  cancel: (id) => http.patch(`/reservations/${id}/cancel`),
  // [GET /api/reservations/{id}] 내 예약 한 건 상세 (SearchReservationDetailResponse)
  // 남의 예약 번호를 넣으면 서버가 막아요. (userId 로 본인 것만 조회)
  // 위치·탑승 시간은 응답에 없어서, 같이 온 attractionId 로 어트랙션 상세를 한 번 더 불러와요.
  detail: async (id) => {
    const r = await http.get(`/reservations/${id}`);
    // 어트랙션이 삭제됐어도 예약 상세는 보여야 하니까, 실패하면 null 로 두고 넘어가요.
    const a = await attractionApi.detail(r.attractionId).catch(() => null);
    return {
      id: r.reservationId,
      number: r.reservationNumber,
      status: RESERVATION_STATUS_MAP[r.status] || r.status,
      date: r.startsAt.slice(0, 10),        // "2026-11-02"
      startTime: toHHmm(r.startsAt),        // "14:30"
      endTime: r.endsAt ? toHHmm(r.endsAt) : null,
      attractionId: r.attractionId,
      attractionName: r.attractionName,
      image: a?.image ?? imageOf(r.attractionName),
      zone: a?.zone ?? '',
      rideMinutes: a?.rideMinutes ?? null,
      ticketId: r.ticketId,
      createdAt: r.createdAt,               // Instant(UTC) → 화면에서 한국 시간으로 바꿔 보여 줘요
      cancelReason: r.cancelReasonMessage,  // 예) "운영 사정으로 인한 취소" (서버가 만든 문장)
      cancelledAt: r.cancelledAt,
    };
  },
};

// ───────── 주문 ✅ (생성·상세·결제 내역 연결) ─────────

// "2026-11-04T15:03:12" → "2026-11-04 15:03" (화면에 보기 좋게)
const toDateTimeText = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '-');

// 주문 응답에는 상품 "번호"만 있어서, 상품 목록을 받아 와 번호로 이름을 찾아요.
async function productsById() {
  const list = await ticketApi.products();
  return new Map(list.map((p) => [p.id, p]));
}

// [주문 상세] SearchOrderResponse → 결제 화면(Checkout.jsx) 모양
// 입장권 구매 화면은 한 번에 한 종류만 사니까 items[0] 만 봐요.
function toOrder(o, byId) {
  const item = o.items?.[0];
  return {
    id: o.orderId,
    status: o.status,                      // PENDING | COMPLETED | ...
    visitDate: o.visitDate,                // 방문일 "2026-11-01" (새로고침해도 서버에서 다시 받아요)
    totalPrice: o.totalAmount,
    productName: byId.get(item?.ticketProductId)?.name ?? '입장권',
    quantity: item?.quantity ?? 0,
    unitPrice: item?.price ?? 0,           // 주문 당시 가격 (지금 상품 가격과 다를 수 있어요)
    pgId: o.pgId,                          // 포트원 결제창에 넣을 결제번호 (새로고침해도 여기서 다시 받아요)
  };
}

export const orderApi = {
  // [POST /api/orders] { visitDate, items: [{ ticketProductId, quantity }] }
  // 응답의 pgId = 포트원 결제창에 넣을 "결제 번호표"예요.
  create: async (date, productId, quantity) => {
    const r = await http.post('/orders', {
      visitDate: date,
      items: [{ ticketProductId: Number(productId), quantity: Number(quantity) }],
    });
    return { id: r.orderId, pgId: r.pgId };
  },

  // [GET /api/orders/{id}]
  detail: async (id) => {
    const [o, byId] = await Promise.all([http.get(`/orders/${id}`), productsById()]);
    return toOrder(o, byId);
  },

  // [POST /api/orders/{id}/cancel] 결제 대기(PENDING) 주문만 취소돼요. 응답: { orderId, status: 'CANCELLED' }
  cancel: (id) => http.post(`/orders/${id}/cancel`),

  // [GET /api/orders/me?page=0&size=20] 내 결제 내역
  // 목록 응답의 items 에 상품 이름·수량이 같이 와요.
  mine: async () => {
    const page = await http.get(`/orders/me${toQuery({ page: 0, size: 20 })}`);
    return page.content.map((o) => ({
      id: o.orderId,
      totalPrice: o.totalAmount,
      status: o.status,
      createdAt: toDateTimeText(o.createdAt),
      // "종일권 × 2, 오후권 × 1" 처럼 한 줄 글자로 만들어요. 상품이 없으면 빈 글자예요.
      productSummary: (o.items ?? [])
          .map((i) => `${i.ticketProductName} × ${i.quantity}`)
          .join(', '),
    }));
  },
};

// ───────── 결제 ✅ ─────────
export const paymentApi = {
  // [GET /api/config/portone] → { storeId, channelKey }
  // 포트원 상점 정보를 프론트 코드에 직접 적지 않고 서버에서 받아 와요.
  config: () => http.get('/config/portone'),

  // [POST /api/payments/{pgId}/confirm] { orderId }
  // 포트원 결제창이 끝난 뒤, 서버가 포트원에 "진짜 돈이 들어왔는지" 다시 물어보고 주문을 확정해요.
  confirm: (pgId, orderId) => http.post(`/payments/${pgId}/confirm`, { orderId: Number(orderId) }),
};

// ───────── 루트 추천 ✅ ─────────

// [추천 결과] SearchRouteRecommendationResponse → 화면 모양
function toRouteResult(res, byId) {
  const stops = res.visits
      .map((v) => {
        const a = byId.get(v.attractionId);
        return {
          order: v.sequence,                       // 방문 순서 1, 2, 3...
          attractionId: v.attractionId,
          name: v.attractionName,
          image: a?.image ?? imageOf(v.attractionName),
          zone: a?.zone ?? '',
          slotId: v.attractionSlotId,              // "모두 예약" 할 때 쓰는 슬롯 번호
          time: toHHmm(v.startsAt),                // 탑승 시작
          endTime: toHHmm(v.endsAt),               // 탑승 끝
          rideMinutes: v.estimatedRideMinutes,
          moveMinutes: v.estimatedMoveMinutes,     // 이전 장소(처음엔 입구)에서 여기까지 이동
          waitMinutes: v.estimatedWaitMinutes,     // 도착해서 탑승 시작까지 기다리는 시간
          arrivalTime: toHHmm(v.estimatedArrivalAt),
        };
      })
      .sort((x, y) => x.order - y.order);

  const excluded = res.excludedAttractions.map((e) => ({ name: e.attractionName, reason: e.reason }));

  return {
    recommendationId: res.recommendationId,    // 추천 없음(NONE)이면 저장되지 않아서 null
    resultType: res.resultType,                // FULL(전부) | PARTIAL(일부) | NONE(없음)
    ticketId: res.ticketId,
    stops,
    totalRideMinutes: res.totalRideMinutes,
    totalWalkMinutes: res.totalMoveMinutes,
    totalWaitMinutes: res.totalWaitMinutes,
    estimatedEndTime: toHHmm(res.estimatedEndAt),
    excluded,
    unavailable: excluded.map((e) => e.name),
  };
}

export const routeApi = {
  // [POST /api/route-recommendations] { ticketId, startAt, endAt, attractionIds }
  // 화면은 "14:00" 같은 시간만 주니까, 입장권 날짜를 붙여 "2026-11-02T14:00:00" 으로 만들어요.
  // (백엔드는 입장권 날짜 기준으로 시간을 검사해요)
  recommend: async ({ ticketId, date, startTime, endTime, attractionIds }) => {
    if (!ticketId) throw new Error('입장 완료된 오늘 입장권이 있어야 루트를 추천받을 수 있어요.');
    if (attractionIds.length > 5) throw new Error('어트랙션은 최대 5개까지 고를 수 있어요.');
    const day = date || todayString();
    const [res, all] = await Promise.all([
      http.post('/route-recommendations', {
        ticketId: Number(ticketId),
        startAt: `${day}T${startTime}:00`,
        endAt: `${day}T${endTime}:00`,
        attractionIds: attractionIds.map(Number),
      }),
      attractionApi.list('ALL'),
    ]);
    // 응답에 사진·위치가 없어서 어트랙션 목록에서 id로 찾아 붙여요.
    return toRouteResult(res, new Map(all.map((a) => [a.id, a])));
  },
};

// ───────── 관리자 – 어트랙션·슬롯 ✅ ─────────

// [관리자 슬롯 한 칸] SearchAdminAttractionSlotResponse.SlotItem → 화면 모양
function toAdminSlot(s) {
  return {
    id: s.slotId,
    startTime: toHHmm(s.startsAt),
    endTime: toHHmm(s.endsAt),
    capacity: s.capacity,
    reservedCount: s.reservedCount,
    remaining: s.remainingCount,
    status: s.status, // OPEN | CLOSED | SUSPENDED
  };
}

// 일괄 생성할 시작 시각: 10:00, 10:30, ... 21:30 (30분 간격)
// 폐장(22:00) 전에 끝나지 않는 시각은 백엔드가 알아서 거절해요.
function operatingStartTimes() {
  const times = [];
  for (let m = 10 * 60; m <= 21 * 60 + 30; m += 30) {
    times.push(`${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`);
  }
  return times;
}

const adminSlotApi = {
  // 관리자 전용 어트랙션 목록 API가 없어서, 공개 목록 API를 그대로 써요. (삭제된 것만 빠져요)
  attractions: () => attractionApi.list('ALL'),

  // [GET /api/admin/attractions/{id}/slots?date=] (삭제된 슬롯은 빼고 와요)
  slots: async (attractionId, date) => {
    const res = await http.get(`/admin/attractions/${attractionId}/slots${toQuery({ date })}`);
    return res.slots
        .map(toAdminSlot)
        .sort((a, b) => a.startTime.localeCompare(b.startTime));
  },

  // [POST /api/admin/attractions/{id}/slots] { startsAt: "2026-11-02T14:00:00", capacity }
  // 화면은 날짜와 시간을 따로 주니까 백엔드 형식으로 합쳐요. 종료 시각은 서버가 탑승 시간으로 계산해요.
  createSlot: (attractionId, { date, startTime, capacity }) => {
    if (!startTime) return Promise.reject(new Error('시작 시간을 입력해 주세요.'));
    return http.post(`/admin/attractions/${attractionId}/slots`, {
      startsAt: `${date}T${startTime}:00`,
      capacity,
    });
  },

  // 일괄 생성 API가 백엔드에 없어서, 슬롯 추가 API를 시간마다 "한 번씩 차례로" 불러요.
  // 이미 있는 시간·지난 시간·폐장 후에 끝나는 시간은 백엔드가 거절하는데, 그건 건너뛰고 셉니다.
  generateSlots: async (attractionId, date) => {
    let created = 0;
    let skipped = 0;
    for (const startTime of operatingStartTimes()) {
      try {
        await adminSlotApi.createSlot(attractionId, { date, startTime, capacity: 20 });
        created += 1;
      } catch {
        skipped += 1;
      }
    }
    return { created, skipped };
  },

  // [PATCH /api/admin/slots/{id}/status] { status }
  updateSlot: (slotId, status) => http.patch(`/admin/slots/${slotId}/status`, { status }),

  // [PATCH /api/admin/slots/{id}/time] { startsAt: "2026-11-04T10:15:00" }
  // 백엔드 규칙: 예약이 있으면 못 바꾸고, 다른 슬롯과 겹치거나 운영 시간을 벗어나면 거절돼요.
  updateSlotTime: (slotId, date, startTime) =>
      http.patch(`/admin/slots/${slotId}/time`, { startsAt: `${date}T${startTime}:00` }),

  // [PATCH /api/admin/slots/{id}/capacity] { capacity }
  // 백엔드 규칙: 1명 이상, 이미 예약한 인원보다 적게 줄일 수 없어요.
  updateSlotCapacity: (slotId, capacity) =>
      http.patch(`/admin/slots/${slotId}/capacity`, { capacity: Number(capacity) }),

  // [DELETE /api/admin/slots/{id}] (진짜로 지우지 않고 CANCELLED 상태로 바꿔요)
  deleteSlot: (slotId) => http.delete(`/admin/slots/${slotId}`),
};

// ───────── 관리자 – 예약·회원 관리 ✅ ─────────

// 화면 상태 이름 ↔ 백엔드 상태 이름 (예약)
// 화면: RESERVED / USED / CANCELED / NO_SHOW   백엔드: RESERVED / COMPLETED / CANCELLED / NO_SHOW
const RESERVATION_STATUS_TO_BACK = { RESERVED: 'RESERVED', USED: 'COMPLETED', CANCELED: 'CANCELLED', NO_SHOW: 'NO_SHOW' };

// Instant("2026-11-02T08:30:00Z") → 한국 시각 "2026-11-02 17:30"
function toKstText(instant) {
  if (!instant) return '';
  const d = new Date(instant);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// [GET /api/admin/users] 회원 목록 (검색·필터 조건이 없어서 100명을 받아 프론트에서 걸러요)
async function fetchAdminUsers() {
  const page = await http.get(`/admin/users${toQuery({ page: 0, size: 100 })}`);
  return page.content;
}

// [관리자 예약 한 건] SearchAdminReservationResponse → 화면 모양
function toAdminReservation(r, userById, attractionById) {
  const u = userById.get(r.userId);
  const a = attractionById.get(r.attractionId);
  return {
    id: r.reservationId,                 // 상태 변경·취소에 쓰는 번호
    number: r.reservationNumber,         // 화면에 보여 줄 예약번호
    status: RESERVATION_STATUS_MAP[r.status] || r.status,
    date: r.startsAt.slice(0, 10),
    time: toHHmm(r.startsAt),
    endTime: toHHmm(r.endsAt),
    attractionName: r.attractionName,
    image: a?.image ?? imageOf(r.attractionName),
    userId: r.userId,
    memberNickname: u?.nickname ?? `회원 #${r.userId}`,
    memberEmail: u?.email ?? '',
    createdAt: toKstText(r.createdAt),
  };
}

// [관리자 회원 한 명] SearchUserListResponse.Item → 화면 모양
function toAdminMember(m) {
  return {
    id: m.id,
    email: m.email,
    nickname: m.nickname,
    role: m.role,
    status: m.status,
    joinedAt: m.createdAt ? m.createdAt.slice(0, 10) : '',
    ticketCount: null,       // 회원 목록 응답에 없어요
    reservationCount: null,  // 회원 목록 응답에 없어요
  };
}

const adminManageApi = {
  // 필터 선택지 + 이름 찾기용 표 (화면에 처음 들어올 때 한 번만 불러와요)
  //  - members     : 회원 선택 목록 (회원 API 에 검색이 없어서 앞쪽 100명까지만 받아요)
  //  - attractions : 어트랙션 선택 목록
  //  - userById / attractionById : 예약 응답의 번호(userId, attractionId) → 닉네임·사진을 찾는 표
  reservationFilterOptions: async () => {
    const [users, attractions] = await Promise.all([fetchAdminUsers(), attractionApi.list('ALL')]);
    return {
      members: users,
      attractions,
      userById: new Map(users.map((u) => [u.id, u])),
      attractionById: new Map(attractions.map((a) => [a.id, a])),
    };
  },

  // [GET /api/admin/reservations?page=&size=&status=&attractionId=&userId=]
  // 필터와 페이징을 모두 "서버에게" 맡겨요. (SearchAdminReservationCondition + Pageable)
  // lookup 은 위 reservationFilterOptions 의 결과예요.
  // 돌려주는 값: { items, page, totalElements, hasNext }
  reservations: async ({ page = 0, size = 20, status = '', attractionId = '', userId = '' } = {}, lookup) => {
    const params = { page, size, attractionId, userId };
    if (status) params.status = RESERVATION_STATUS_TO_BACK[status];
    const res = await http.get(`/admin/reservations${toQuery(params)}`); // 빈 값은 toQuery 가 빼 줘요
    return {
      items: res.content.map((r) => toAdminReservation(r, lookup.userById, lookup.attractionById)),
      // ⚠️ 이 응답은 페이지 번호 이름이 page 가 아니라 number 예요. (SearchAdminReservationListResponse)
      page: res.number,
      totalElements: res.totalElements,
      hasNext: res.number + 1 < res.totalPages,
    };
  },

  // [GET /api/admin/reservations/{id}] 예약 한 건 상세 (카드를 펼칠 때, 상태를 바꾼 뒤에 불러와요)
  // 목록 응답에 없는 입장권 번호·취소 사유·취소 시각이 더 들어 있어요.
  reservationDetail: async (id, lookup) => {
    const r = await http.get(`/admin/reservations/${id}`);
    return {
      ...toAdminReservation(r, lookup.userById, lookup.attractionById),
      ticketId: r.ticketId,
      cancelReason: r.cancelReasonMessage,
      cancelledAt: toKstText(r.cancelledAt),
    };
  },

  // [PATCH /api/admin/reservations/{id}/status] { status: COMPLETED | NO_SHOW }
  // 백엔드 규칙: "예약 완료" 상태에서만, "이용 완료" 또는 "노쇼"로만 바꿀 수 있어요.
  updateReservation: (id, status) =>
      http.patch(`/admin/reservations/${id}/status`, { status: RESERVATION_STATUS_TO_BACK[status] }),

  // [PATCH /api/admin/reservations/{id}/cancel] { cancelReason }
  cancelReservation: (id, cancelReason = 'OPERATIONAL_ISSUE') =>
      http.patch(`/admin/reservations/${id}/cancel`, { cancelReason }),

  // [GET /api/admin/users] 백엔드에 검색·필터가 없어서 프론트에서 걸러요.
  members: async ({ q = '', status = '', role = '' } = {}) => {
    const keyword = q.trim().toLowerCase();
    return (await fetchAdminUsers())
        .map(toAdminMember)
        .filter((m) => !status || m.status === status)
        .filter((m) => !role || m.role === role)
        .filter((m) => !keyword || m.nickname.toLowerCase().includes(keyword) || m.email.toLowerCase().includes(keyword));
  },

  // [PATCH /api/admin/users/{id}/status] { status }
  // 백엔드 규칙: 활성 ↔ 비활성/정지 만 가능, 탈퇴 회원은 바꿀 수 없어요.
  updateMember: (id, status) => http.patch(`/admin/users/${id}/status`, { status }),
};

// ───────── 관리자 – 어트랙션 등록·수정·상태·삭제 ✅ ─────────

// 입력 화면의 값(글자) → 백엔드 요청 형식 (숫자 칸은 숫자로, 글자 칸은 앞뒤 공백 제거)
function toAttractionBody(form) {
  return {
    name: form.name.trim(),
    description: form.description.trim(),
    location: form.location.trim(),
    durationMinutes: Number(form.durationMinutes),
    entranceMoveMinutes: Number(form.entranceMoveMinutes),
  };
}

const adminAttractionApi = {
  // [GET /api/attractions/{id}] 수정 화면에 채울 값 (입구 이동 시간까지 들어 있는 상세 API를 써요)
  attractionForEdit: async (id) => {
    const d = await http.get(`/attractions/${id}`);
    return {
      id: d.attractionId,
      name: d.name,
      description: d.description,
      location: d.location,
      durationMinutes: String(d.durationMinutes),
      entranceMoveMinutes: String(d.entranceMoveMinutes),
      status: d.operationStatus, // OPERATING | MAINTENANCE | SUSPENDED
    };
  },

  // [POST /api/admin/attractions] → 새 어트랙션 번호를 돌려줘요
  createAttraction: async (form) => {
    const r = await http.post('/admin/attractions', toAttractionBody(form));
    return { id: r.attractionId };
  },

  // [PATCH /api/admin/attractions/{id}] 정보 수정
  updateAttraction: (id, form) => http.patch(`/admin/attractions/${id}`, toAttractionBody(form)),

  // [PATCH /api/admin/attractions/{id}/status] { operationStatus }
  // 백엔드 규칙: 같은 상태로는 못 바꾸고, 운영중이 아닌 상태로 바꿀 때 이용 예정 예약이 있으면 막혀요.
  updateAttractionStatus: (id, status) =>
      http.patch(`/admin/attractions/${id}/status`, { operationStatus: status }),

  // [DELETE /api/admin/attractions/{id}] 진짜로 지우지 않고 "삭제됨" 표시만 해요 (이용 예정 예약이 있으면 막혀요)
  deleteAttraction: (id) => http.delete(`/admin/attractions/${id}`),
};

// ───────── 관리자 주문 · 환불 ✅ ─────────

// [관리자 주문 목록 한 줄] SearchAdminOrderListResponse.Item → 화면 모양
function toAdminOrder(o) {
  return {
    id: o.orderId,
    userId: o.userId,
    email: o.email,
    nickname: o.nickname,
    totalPrice: o.totalAmount,
    status: o.status,           // PENDING | COMPLETED | PARTIALLY_REFUNDED | REFUNDED | CANCELLED
    visitDate: o.visitDate,
    createdAt: toDateTimeText(o.createdAt),
  };
}

const adminOrderApi = {
  // [GET /api/admin/orders?page=0&size=50&status=&userId=]
  // 빈 값(전체 상태, 회원번호 없음)은 toQuery 가 알아서 빼고 보내요.
  orders: async ({ status, userId } = {}) => {
    const page = await http.get(`/admin/orders${toQuery({ page: 0, size: 50, status, userId })}`);
    return page.content.map(toAdminOrder);
  },

  // [GET /api/admin/orders/{id}] 상세: 주문 상품, 발급된 입장권, 결제번호(pgId)가 같이 와요.
  // 주문 상품에는 상품 "번호"만 있어서, 상품 목록에서 이름을 찾아 붙여요.
  orderDetail: async (id) => {
    const [o, byId] = await Promise.all([http.get(`/admin/orders/${id}`), productsById()]);
    return {
      ...toAdminOrder(o),
      pgId: o.pgId,
      items: o.items.map((i) => ({
        id: i.id,
        productName: byId.get(i.ticketProductId)?.name ?? `상품 #${i.ticketProductId}`,
        price: i.price,         // 주문 당시 가격
        quantity: i.quantity,
      })),
      // 부분 환불할 입장권을 고를 때 써요. 결제 전 주문은 빈 목록이에요.
      tickets: (o.tickets ?? []).map((t) => ({
        id: t.ticketId,
        orderItemId: t.orderItemId, // 어느 주문 상품(단가)에 속한 입장권인지
        name: t.name,
        status: t.status,           // UNUSED | IN_USE | USED | REFUNDED | EXPIRED
        validDate: t.validDate,
      })),
    };
  },

  // [POST /api/admin/orders/{id}/cancel] 결제 대기(PENDING) 주문만 취소할 수 있어요.
  cancelOrder: (id) => http.post(`/admin/orders/${id}/cancel`),

  // [POST /api/admin/payments/{pgId}/refunds] { reason }
  // 관리자 전체 환불: 그 주문의 "미사용" 입장권을 한꺼번에 환불해요.
  refundOrder: (pgId, reason) => http.post(`/admin/payments/${pgId}/refunds`, { reason }),

  // [POST /api/admin/payments/{pgId}/refunds/partial] { ticketIds: [입장권번호], reason }
  // 관리자 부분 환불: 고른 입장권만 환불해요. 미사용이 아닌 입장권이 섞이면 서버가 거절해요.
  refundOrderPartial: (pgId, ticketIds, reason) =>
      http.post(`/admin/payments/${pgId}/refunds/partial`, { ticketIds, reason }),
};

// ───────── 관리자 입장권 상품 ✅ ─────────

// [관리자 상품 한 개] SearchTicketProductResponse → 관리 화면 모양
// 수정 요청에 version 이 꼭 필요해서(동시 수정 방지) 그대로 들고 있어요.
function toAdminProduct(p) {
  return {
    id: p.ticketProductId,
    name: p.name,
    type: p.type,               // ALL_DAY | AFTERNOON (백엔드 이름 그대로: 등록할 때 그대로 보내요)
    typeLabel: TICKET_TYPE_LABEL[p.type] ?? p.type,
    price: p.price,
    description: p.description,
    status: p.status,           // ON_SALE | STOPPED | ENDED | OUT_OF_STOCK
    stock: p.stock,
    version: p.version,
  };
}
const TICKET_TYPE_LABEL = { ALL_DAY: '종일권', AFTERNOON: '오후권' };

const adminProductApi = {
  // [GET /api/ticket-products?size=50] 삭제(DELETED)된 상품은 서버가 빼고 보내요.
  adminProducts: async () => {
    const page = await http.get(`/ticket-products${toQuery({ size: 50 })}`);
    return page.content.map(toAdminProduct);
  },

  // [POST /api/admin/ticket-products] { name, type, price, description, saleStatus, stock }
  createProduct: (form) =>
      http.post('/admin/ticket-products', {
        name: form.name.trim(),
        type: form.type,
        price: Number(form.price),
        description: form.description.trim(),
        saleStatus: form.status,
        stock: Number(form.stock),
      }),

  // [PUT /api/admin/ticket-products/{id}] { name, price, description, saleStatus, stock, version }
  // 종류(type)는 수정할 수 없어요. version 이 서버 값과 다르면 "다른 요청에 의해 변경" 오류가 나요.
  updateProduct: (id, form) =>
      http.put(`/admin/ticket-products/${id}`, {
        name: form.name.trim(),
        price: Number(form.price),
        description: form.description.trim(),
        saleStatus: form.status,
        stock: Number(form.stock),
        version: form.version,
      }),

  // [DELETE /api/admin/ticket-products/{id}] 진짜로 지우지 않고 DELETED 상태로 바꿔요.
  deleteProduct: (id) => http.delete(`/admin/ticket-products/${id}`),
};

// ───────── 관리자 입장 처리 ✅ ─────────

// Instant(예: "2026-11-05T01:30:00Z") → 한국 시각 "2026-11-05 10:30"
// 서버 설정에 따라 숫자(초)로 올 수도 있어서 둘 다 받아요.
function toKstDateTimeText(v) {
  if (v === null || v === undefined) return '-';
  const date = typeof v === 'number' ? new Date(v * 1000) : new Date(v);
  if (Number.isNaN(date.getTime())) return String(v);
  const parts = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).formatToParts(date);
  const get = (type) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}-${get('month')}-${get('day')} ${get('hour')}:${get('minute')}`;
}

const adminTicketApi = {
  // [POST /api/admin/tickets/entry] { ticketCode } → CreateTicketEntryResponse
  // 입장 처리에 성공하면 입장권이 UNUSED → IN_USE 로 바뀌고, 그때부터 어트랙션 예약·루트 추천을 할 수 있어요.
  enterTicket: async (ticketCode) => {
    const t = await http.post('/admin/tickets/entry', { ticketCode });
    return {
      id: t.ticketId,
      code: t.ticketCode,
      type: TICKET_TYPE_MAP[t.type] || t.type, // ALL_DAY → FULL_DAY (화면이 쓰는 이름)
      date: t.validDate,
      status: t.status,                         // 성공하면 IN_USE
      enteredAt: toKstDateTimeText(t.enteredAt),
    };
  },
};

export const adminApi = {
  ...adminSlotApi,
  ...adminManageApi,
  ...adminAttractionApi,
  ...adminOrderApi,
  ...adminProductApi,
  ...adminTicketApi,
};