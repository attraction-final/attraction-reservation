/*
 * ⚠️ 가짜 서버 (백엔드가 생기기 전까지만 쓰는 파일)
 *
 * 스프링부트가 해 줄 일을 브라우저 안에서 흉내 냅니다.
 * - 데이터는 아래 배열(=가짜 DB)에 들어 있고, 새로고침하면 처음 상태로 돌아가요.
 * - 여기 나오는 이름·숫자·가격은 화면 확인용 "예시 값"이에요. 실제 값이 아닙니다.
 * - 진짜 백엔드를 붙이면(.env 의 VITE_USE_MOCK=false) 이 파일은 사용되지 않아요.
 *
 * 스프링부트 코드를 짤 때 "이 주소로 오면 이런 JSON을 돌려주면 되는구나"를
 * 확인하는 참고서로도 쓸 수 있어요.
 */
import { ApiError } from './ApiError.js';

// 디자인과 똑같이 보이도록 "오늘"과 "지금 시각"을 고정해 둡니다.
const TODAY = '2026-10-24';
const NOW = '13:20';
const WALK_MINUTES = 10; // 어트랙션 사이 이동 시간(예시)

// ───────── 가짜 DB ─────────
const members = [
  { id: 1024, nickname: '테스트회원', email: 'test@example.com', password: '1234', role: 'USER', status: 'ACTIVE', joinedAt: '2026-09-01' },
  { id: 1023, nickname: '회원B', email: 'b@example.com', password: '1234', role: 'USER', status: 'SUSPENDED', joinedAt: '2026-08-21' },
  { id: 1022, nickname: '관리자C', email: 'admin@example.com', password: '1234', role: 'ADMIN', status: 'ACTIVE', joinedAt: '2026-08-02' },
  { id: 1021, nickname: '회원D', email: 'd@example.com', password: '1234', role: 'USER', status: 'WITHDRAWN', joinedAt: '2026-07-15' },
  { id: 1020, nickname: '회원E', email: 'e@example.com', password: '1234', role: 'USER', status: 'INACTIVE', joinedAt: '2026-07-01' },
];

const attractions = [
  { id: 1, name: 'T 익스프레스', image: '/images/t-express.jpg', zone: '어드벤처존', rideMinutes: 3, status: 'OPERATING', heightLimited: true },
  { id: 2, name: '롤링 엑스 트레인', image: '/images/rolling-x-train.jpg', zone: '스카이존', rideMinutes: 4, status: 'OPERATING', heightLimited: true },
  { id: 3, name: '아마존 익스프레스', image: '/images/amazon-express.jpg', zone: '레이크존', rideMinutes: 12, status: 'OPERATING', heightLimited: false },
  { id: 4, name: '썬더폴스', image: '/images/thunder-falls.jpg', zone: '레이크존', rideMinutes: 5, status: 'MAINTENANCE', heightLimited: false },
  { id: 5, name: '사파리 월드', image: '/images/safari-world.jpg', zone: '사파리존', rideMinutes: 15, status: 'OPERATING', heightLimited: false },
  { id: 6, name: '슈팅 고스트', image: '/images/shooting-ghost.jpg', zone: '캐슬존', rideMinutes: 15, status: 'OPERATING', heightLimited: false },
  { id: 7, name: '회전 디스크', image: '/images/disk.jpg', zone: '스카이존', rideMinutes: 3, status: 'OPERATING', heightLimited: true },
  { id: 8, name: '윈드밀', image: '/images/windmill.jpg', zone: '스카이존', rideMinutes: 3, status: 'OPERATING', heightLimited: true },
  { id: 9, name: '해적선 회전배', image: '/images/pirate-boat.jpg', zone: '키즈존', rideMinutes: 5, status: 'OPERATING', heightLimited: false },
  { id: 10, name: '범퍼카', image: '/images/bumper-car.jpg', zone: '키즈존', rideMinutes: 5, status: 'OPERATING', heightLimited: false },
];

// 오늘의 예약 슬롯을 만들어 둡니다. (어트랙션마다 13:30 ~ 16:30, 30분 간격)
let nextSlotId = 1;
const SLOT_TIMES = ['13:30', '14:00', '14:30', '15:00', '15:30', '16:00', '16:30'];
const BASE_RESERVED = [5, 12, 17, 20, 9, 8, 6];
const slots = [];
attractions.forEach((a) => {
  SLOT_TIMES.forEach((time, i) => {
    const reserved = a.id === 9 ? 20 : Math.max(0, BASE_RESERVED[(i + a.id - 1) % BASE_RESERVED.length] - (a.id === 1 ? 0 : 2));
    slots.push({
      id: nextSlotId++,
      attractionId: a.id,
      date: TODAY,
      startTime: time,
      capacity: 20,
      reservedCount: reserved,
      status: a.status === 'OPERATING' ? 'OPEN' : 'CLOSED',
    });
  });
});

const findSlot = (attractionId, date, time) =>
  slots.find((s) => s.attractionId === attractionId && s.date === date && s.startTime === time);

let nextReservationNo = 125;
const reservations = [
  { id: 'R-000123', memberId: 1024, attractionId: 1, date: TODAY, time: '14:00', ticketId: 'T-000001', status: 'RESERVED', createdAt: '2026-10-23 18:42' },
  { id: 'R-000124', memberId: 1024, attractionId: 2, date: TODAY, time: '15:30', ticketId: 'T-000001', status: 'RESERVED', createdAt: '2026-10-23 18:45' },
  { id: 'R-000110', memberId: 1024, attractionId: 3, date: '2026-10-12', time: '16:00', ticketId: 'T-000003', status: 'USED', createdAt: '2026-10-11 20:10' },
  { id: 'R-000122', memberId: 1023, attractionId: 2, date: TODAY, time: '13:00', ticketId: 'T-000020', status: 'USED', createdAt: '2026-10-22 09:12' },
  { id: 'R-000121', memberId: 1022, attractionId: 3, date: TODAY, time: '15:30', ticketId: 'T-000021', status: 'CANCELED', createdAt: '2026-10-22 10:03' },
  { id: 'R-000120', memberId: 1021, attractionId: 1, date: TODAY, time: '11:00', ticketId: 'T-000022', status: 'NO_SHOW', createdAt: '2026-10-21 15:40' },
  { id: 'R-000119', memberId: 1020, attractionId: 2, date: TODAY, time: '16:30', ticketId: 'T-000023', status: 'RESERVED', createdAt: '2026-10-21 11:27' },
].map((r) => ({ ...r, slotId: findSlot(r.attractionId, r.date, r.time)?.id ?? null }));

// 예시 가격입니다. 실제 가격으로 바꿔 주세요.
const products = [
  { id: 1, type: 'FULL_DAY', name: '종일권', hours: '개장 ~ 폐장', entryFrom: null, price: 50000 },
  { id: 2, type: 'AFTERNOON', name: '오후권', hours: '15:00 ~ 폐장', entryFrom: '15:00', price: 40000 },
];

let nextTicketNo = 6;
const tickets = [
  { id: 'T-000001', memberId: 1024, type: 'FULL_DAY', date: TODAY, status: 'UNUSED', orderId: 'O-000000' },
  { id: 'T-000002', memberId: 1024, type: 'FULL_DAY', date: TODAY, status: 'IN_USE', orderId: 'O-000000' },
  { id: 'T-000003', memberId: 1024, type: 'AFTERNOON', date: '2026-10-12', status: 'USED', orderId: 'O-000001' },
  { id: 'T-000004', memberId: 1024, type: 'FULL_DAY', date: '2026-10-05', status: 'REFUNDED', orderId: 'O-000001' },
  { id: 'T-000005', memberId: 1024, type: 'FULL_DAY', date: '2026-09-20', status: 'EXPIRED', orderId: 'O-000001' },
];

let nextOrderNo = 2;
const orders = [
  { id: 'O-000000', memberId: 1024, date: TODAY, productId: 1, quantity: 2, unitPrice: 50000, status: 'PAID', paidAt: '2026-10-20', refundedQuantity: 0 },
  { id: 'O-000001', memberId: 1024, date: '2026-10-12', productId: 2, quantity: 3, unitPrice: 40000, status: 'PARTIAL_REFUNDED', paidAt: '2026-10-08', refundedQuantity: 1 },
];

let sessionMemberId = 1024; // 처음엔 테스트회원으로 로그인된 상태로 시작

// ───────── 도우미 ─────────
const pad = (n) => String(n).padStart(6, '0');
const toMin = (hhmm) => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; };
const toHHMM = (min) => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
const fail = (status, message) => { throw new ApiError(message, status); };

function requireLogin() {
  const m = members.find((x) => x.id === sessionMemberId);
  if (!m) fail(401, '로그인이 필요해요.');
  return m;
}
function requireAdmin() {
  const m = requireLogin();
  if (m.role !== 'ADMIN') {
    // 연습용이라 일반 회원도 관리자 화면을 볼 수 있게 열어 두었어요.
    // 실제 서버에서는 여기서 403(권한 없음)을 돌려줘야 합니다.
  }
  return m;
}

function memberView(m) {
  return { id: m.id, nickname: m.nickname, email: m.email, role: m.role, status: m.status, joinedAt: m.joinedAt };
}

function slotView(slot, memberId) {
  const remaining = slot.capacity - slot.reservedCount;
  const mine = reservations.find((r) => r.memberId === memberId && r.status === 'RESERVED' && r.slotId === slot.id);
  const clash = reservations.find((r) => r.memberId === memberId && r.status === 'RESERVED' && r.slotId !== slot.id
    && r.date === slot.date && r.time === slot.startTime);
  let reason = null; // null 이면 예약 가능
  if (slot.status === 'CLOSED') reason = 'CLOSED';
  else if (slot.date === TODAY && toMin(slot.startTime) < toMin(NOW) + 30) reason = 'PAST';
  else if (mine) reason = 'MINE';
  else if (remaining <= 0) reason = 'FULL';
  else if (clash) reason = 'CONFLICT';
  return { id: slot.id, startTime: slot.startTime, capacity: slot.capacity, remaining, status: slot.status, bookable: reason === null, reason };
}

function attractionSummary(a) {
  const today = slots.filter((s) => s.attractionId === a.id && s.date === TODAY).map((s) => slotView(s, sessionMemberId));
  const next = today.find((s) => s.bookable || s.reason === 'MINE' || s.reason === 'CONFLICT');
  const nextOpen = today.find((s) => s.bookable);
  return {
    id: a.id, name: a.name, image: a.image, zone: a.zone, rideMinutes: a.rideMinutes,
    status: a.status, heightLimited: a.heightLimited,
    soldOut: a.status === 'OPERATING' && !next,
    nextSlot: nextOpen ? { time: nextOpen.startTime, remaining: nextOpen.remaining } : null,
  };
}

function reservationView(r) {
  const a = attractions.find((x) => x.id === r.attractionId);
  const m = members.find((x) => x.id === r.memberId);
  const s = slots.find((x) => x.id === r.slotId);
  const t = tickets.find((x) => x.id === r.ticketId);
  return {
    id: r.id, status: r.status, date: r.date, time: r.time, createdAt: r.createdAt,
    attractionId: a.id, attractionName: a.name, image: a.image, zone: a.zone, rideMinutes: a.rideMinutes,
    ticketId: r.ticketId, ticketType: t?.type ?? 'FULL_DAY',
    memberId: m.id, memberNickname: m.nickname, memberEmail: m.email,
    slotRemaining: s ? s.capacity - s.reservedCount : null, slotCapacity: s?.capacity ?? null,
  };
}

function ticketView(t) {
  const m = members.find((x) => x.id === t.memberId);
  return { id: t.id, type: t.type, date: t.date, status: t.status, orderId: t.orderId, ownerNickname: m.nickname };
}

function orderView(o) {
  const p = products.find((x) => x.id === o.productId);
  return {
    id: o.id, date: o.date, productId: p.id, productName: p.name, productType: p.type,
    quantity: o.quantity, unitPrice: o.unitPrice, totalPrice: o.unitPrice * o.quantity,
    status: o.status, paidAt: o.paidAt, refundedQuantity: o.refundedQuantity,
  };
}

// ───────── 주소별 처리 (스프링부트의 @Controller 역할) ─────────
const routes = [
  // 회원
  ['POST', '/auth/login', (p, q, body) => {
    const m = members.find((x) => x.email === body?.email && x.password === body?.password);
    if (!m) fail(401, '이메일 또는 비밀번호가 올바르지 않습니다.');
    if (m.status === 'SUSPENDED' || m.status === 'WITHDRAWN') fail(403, '이용할 수 없는 계정이에요.');
    sessionMemberId = m.id;
    return memberView(m);
  }],
  ['POST', '/auth/signup', (p, q, body) => {
    if (!body?.nickname || !body?.email || !body?.password) fail(400, '닉네임, 이메일, 비밀번호를 모두 입력해 주세요.');
    if (members.some((x) => x.email === body.email)) fail(409, '이미 가입된 이메일이에요.');
    const m = { id: Math.max(...members.map((x) => x.id)) + 1, nickname: body.nickname, email: body.email, password: body.password, role: 'USER', status: 'ACTIVE', joinedAt: TODAY };
    members.push(m);
    sessionMemberId = m.id;
    return memberView(m);
  }],
  ['POST', '/auth/logout', () => { sessionMemberId = null; return null; }],
  ['GET', '/members/me', () => memberView(requireLogin())],
  ['PATCH', '/members/me', (p, q, body) => {
    const m = requireLogin();
    if (!body?.nickname?.trim()) fail(400, '닉네임을 입력해 주세요.');
    m.nickname = body.nickname.trim();
    return memberView(m);
  }],

  // 파크
  ['GET', '/park/today', () => ({
    date: TODAY, now: NOW, openTime: '10:00', closeTime: '22:00',
    operatingCount: attractions.filter((a) => a.status === 'OPERATING').length,
    maintenanceCount: attractions.filter((a) => a.status !== 'OPERATING').length,
  })],
  ['GET', '/park/sale-dates', () => ['2026-10-19', '2026-10-20', '2026-10-21', '2026-10-22', '2026-10-23', '2026-10-24', '2026-10-25']
    .map((d) => ({ date: d, available: d !== '2026-10-23' }))],

  // 어트랙션
  ['GET', '/attractions', (p, q) => {
    const filter = q.get('filter') || 'ALL';
    return attractions.map(attractionSummary).filter((a) =>
      filter === 'ALL'
      || (filter === 'AVAILABLE' && a.status === 'OPERATING' && !a.soldOut)
      || (filter === 'HEIGHT' && a.heightLimited));
  }],
  ['GET', '/attractions/:id', (p) => {
    const a = attractions.find((x) => x.id === Number(p.id));
    if (!a) fail(404, '어트랙션을 찾을 수 없어요.');
    return { ...attractionSummary(a), description: null, heightLimitCm: null };
  }],
  ['GET', '/attractions/:id/slots', (p, q) => {
    const date = q.get('date') || TODAY;
    return slots.filter((s) => s.attractionId === Number(p.id) && s.date === date)
      .sort((x, y) => toMin(x.startTime) - toMin(y.startTime))
      .map((s) => slotView(s, sessionMemberId));
  }],

  // 예약
  ['GET', '/reservations/me', (p, q) => {
    const me = requireLogin();
    const scope = q.get('scope') || 'UPCOMING';
    return reservations
      .filter((r) => r.memberId === me.id && (scope === 'ALL' || r.status === 'RESERVED'))
      .sort((a, b) => (a.date + a.time).localeCompare(b.date + b.time))
      .map(reservationView);
  }],
  ['POST', '/reservations', (p, q, body) => {
    const me = requireLogin();
    const slot = slots.find((s) => s.id === Number(body?.slotId));
    if (!slot) fail(404, '예약 시간을 찾을 수 없어요.');
    const view = slotView(slot, me.id);
    if (!view.bookable) {
      const msg = { CLOSED: '운영하지 않는 시간이에요.', PAST: '예약 가능 시간이 지났어요.', MINE: '이미 예약한 시간이에요.', FULL: '정원이 다 찼어요.', CONFLICT: '같은 시간에 다른 예약이 있어요.' };
      fail(409, msg[view.reason]);
    }
    const ticket = tickets.find((t) => t.id === body?.ticketId && t.memberId === me.id);
    if (!ticket || !['UNUSED', 'IN_USE'].includes(ticket.status) || ticket.date !== slot.date) fail(400, '사용할 수 있는 입장권이 아니에요.');
    if (ticket.type === 'AFTERNOON' && toMin(slot.startTime) < toMin('15:00')) fail(400, '오후권은 15:00 이후만 예약할 수 있어요.');
    slot.reservedCount += 1;
    const r = { id: `R-${pad(nextReservationNo++)}`, memberId: me.id, attractionId: slot.attractionId, date: slot.date, time: slot.startTime, slotId: slot.id, ticketId: ticket.id, status: 'RESERVED', createdAt: `${TODAY} ${NOW}` };
    reservations.push(r);
    return reservationView(r);
  }],
  ['DELETE', '/reservations/:id', (p) => {
    const me = requireLogin();
    const r = reservations.find((x) => x.id === p.id && x.memberId === me.id);
    if (!r) fail(404, '예약을 찾을 수 없어요.');
    if (r.status !== 'RESERVED') fail(409, '취소할 수 없는 예약이에요.');
    r.status = 'CANCELED';
    const s = slots.find((x) => x.id === r.slotId);
    if (s) s.reservedCount -= 1;
    return null;
  }],

  // 입장권 / 주문
  ['GET', '/ticket-products', () => products],
  ['GET', '/tickets/me', (p, q) => {
    const me = requireLogin();
    const scope = q.get('scope') || 'ALL';
    const usable = (t) => ['UNUSED', 'IN_USE'].includes(t.status);
    return tickets
      .filter((t) => t.memberId === me.id && (scope === 'ALL' || (scope === 'USABLE' ? usable(t) : !usable(t))))
      .sort((a, b) => b.date.localeCompare(a.date) || a.id.localeCompare(b.id))
      .map(ticketView);
  }],
  ['POST', '/tickets/:id/refund', (p) => {
    const me = requireLogin();
    const t = tickets.find((x) => x.id === p.id && x.memberId === me.id);
    if (!t) fail(404, '입장권을 찾을 수 없어요.');
    if (t.status !== 'UNUSED') fail(409, '미사용 입장권만 환불할 수 있어요.');
    t.status = 'REFUNDED';
    const o = orders.find((x) => x.id === t.orderId);
    if (o) { o.refundedQuantity += 1; o.status = o.refundedQuantity >= o.quantity ? 'REFUNDED' : 'PARTIAL_REFUNDED'; }
    return ticketView(t);
  }],
  ['POST', '/orders', (p, q, body) => {
    const me = requireLogin();
    const product = products.find((x) => x.id === Number(body?.productId));
    if (!product) fail(400, '입장권 종류를 골라 주세요.');
    const qty = Number(body?.quantity);
    if (!qty || qty < 1 || qty > 10) fail(400, '수량은 1~10매까지 고를 수 있어요.');
    const o = { id: `O-${pad(nextOrderNo++)}`, memberId: me.id, date: body.date, productId: product.id, quantity: qty, unitPrice: product.price, status: 'PENDING', paidAt: null, refundedQuantity: 0 };
    orders.push(o);
    return orderView(o);
  }],
  ['GET', '/orders/me', () => {
    const me = requireLogin();
    return orders.filter((o) => o.memberId === me.id && o.status !== 'PENDING').reverse().map(orderView);
  }],
  ['GET', '/orders/:id', (p) => {
    const me = requireLogin();
    const o = orders.find((x) => x.id === p.id && x.memberId === me.id);
    if (!o) fail(404, '주문을 찾을 수 없어요.');
    return orderView(o);
  }],
  ['POST', '/orders/:id/pay', (p, q, body) => {
    const me = requireLogin();
    const o = orders.find((x) => x.id === p.id && x.memberId === me.id);
    if (!o) fail(404, '주문을 찾을 수 없어요.');
    if (o.status !== 'PENDING') fail(409, '이미 결제된 주문이에요.');
    if (!body?.method) fail(400, '결제 수단을 골라 주세요.');
    o.status = 'PAID';
    o.paidAt = TODAY;
    const product = products.find((x) => x.id === o.productId);
    for (let i = 0; i < o.quantity; i++) {
      tickets.push({ id: `T-${pad(nextTicketNo++)}`, memberId: me.id, type: product.type, date: o.date, status: 'UNUSED', orderId: o.id });
    }
    return orderView(o);
  }],

  // 루트 추천: 고른 어트랙션을 "가장 빨리 탈 수 있는 순서"로 이어 붙여요.
  ['POST', '/routes/recommend', (p, q, body) => {
    const me = requireLogin();
    const ticket = tickets.find((t) => t.id === body?.ticketId);
    let cursor = Math.max(toMin(body?.startTime || '10:00'), toMin(NOW) + 30, ticket?.type === 'AFTERNOON' ? toMin('15:00') : 0);
    const end = toMin(body?.endTime || '22:00');
    let left = attractions.filter((a) => (body?.attractionIds || []).includes(a.id));
    const stops = [];
    while (left.length) {
      const candidates = left.map((a) => {
        const s = slots
          .filter((x) => x.attractionId === a.id && x.date === TODAY)
          .map((x) => slotView(x, me.id))
          .find((x) => x.bookable && toMin(x.startTime) >= cursor && toMin(x.startTime) <= end);
        return s ? { a, s } : null;
      }).filter(Boolean).sort((x, y) => toMin(x.s.startTime) - toMin(y.s.startTime));
      if (!candidates.length) break;
      const { a, s } = candidates[0];
      stops.push({ order: stops.length + 1, attractionId: a.id, name: a.name, image: a.image, zone: a.zone, rideMinutes: a.rideMinutes, slotId: s.id, time: s.startTime, remaining: s.remaining });
      cursor = toMin(s.startTime) + a.rideMinutes + WALK_MINUTES;
      left = left.filter((x) => x.id !== a.id);
    }
    return {
      ticketId: ticket?.id ?? null,
      stops,
      totalRideMinutes: stops.reduce((sum, s) => sum + s.rideMinutes, 0),
      totalWalkMinutes: Math.max(0, stops.length - 1) * WALK_MINUTES,
      walkMinutesBetween: WALK_MINUTES,
      unavailable: left.map((a) => a.name),
    };
  }],

  // ───────── 관리자 ─────────
  ['GET', '/admin/attractions', () => { requireAdmin(); return attractions.map((a) => ({ id: a.id, name: a.name, image: a.image, zone: a.zone, rideMinutes: a.rideMinutes, status: a.status })); }],
  ['GET', '/admin/attractions/:id/slots', (p, q) => {
    requireAdmin();
    const date = q.get('date') || TODAY;
    return slots.filter((s) => s.attractionId === Number(p.id) && s.date === date)
      .sort((x, y) => toMin(x.startTime) - toMin(y.startTime))
      .map((s) => ({ id: s.id, startTime: s.startTime, capacity: s.capacity, reservedCount: s.reservedCount, remaining: s.capacity - s.reservedCount, status: s.status }));
  }],
  ['POST', '/admin/attractions/:id/slots', (p, q, body) => {
    requireAdmin();
    if (!/^\d{2}:\d{2}$/.test(body?.startTime || '')) fail(400, '시작 시간을 입력해 주세요.');
    const capacity = Number(body?.capacity);
    if (!capacity || capacity < 1) fail(400, '정원은 1명 이상이어야 해요.');
    const date = body.date || TODAY;
    if (findSlot(Number(p.id), date, body.startTime)) fail(409, '같은 시간 슬롯이 이미 있어요.');
    const s = { id: nextSlotId++, attractionId: Number(p.id), date, startTime: body.startTime, capacity, reservedCount: 0, status: 'OPEN' };
    slots.push(s);
    return s;
  }],
  ['POST', '/admin/attractions/:id/slots/generate', (p, q, body) => {
    requireAdmin();
    const date = body?.date || TODAY;
    let created = 0;
    for (let m = toMin('10:00'); m <= toMin('21:30'); m += 30) {
      if (!findSlot(Number(p.id), date, toHHMM(m))) {
        slots.push({ id: nextSlotId++, attractionId: Number(p.id), date, startTime: toHHMM(m), capacity: 20, reservedCount: 0, status: 'OPEN' });
        created += 1;
      }
    }
    return { created };
  }],
  ['PATCH', '/admin/slots/:id', (p, q, body) => {
    requireAdmin();
    const s = slots.find((x) => x.id === Number(p.id));
    if (!s) fail(404, '슬롯을 찾을 수 없어요.');
    if (!['OPEN', 'CLOSED'].includes(body?.status)) fail(400, '상태 값이 올바르지 않아요.');
    s.status = body.status;
    return s;
  }],
  ['DELETE', '/admin/slots/:id', (p) => {
    requireAdmin();
    const i = slots.findIndex((x) => x.id === Number(p.id));
    if (i < 0) fail(404, '슬롯을 찾을 수 없어요.');
    if (slots[i].reservedCount > 0) fail(409, '예약이 있는 슬롯은 삭제할 수 없어요.');
    slots.splice(i, 1);
    return null;
  }],
  ['GET', '/admin/reservations', (p, q) => {
    requireAdmin();
    const keyword = (q.get('q') || '').toLowerCase();
    const status = q.get('status');
    return reservations.map(reservationView)
      .filter((r) => !status || r.status === status)
      .filter((r) => !keyword || [r.id, r.memberNickname, r.memberEmail].some((v) => v.toLowerCase().includes(keyword)))
      .sort((a, b) => b.id.localeCompare(a.id));
  }],
  ['PATCH', '/admin/reservations/:id', (p, q, body) => {
    requireAdmin();
    const r = reservations.find((x) => x.id === p.id);
    if (!r) fail(404, '예약을 찾을 수 없어요.');
    if (!['RESERVED', 'USED', 'NO_SHOW'].includes(body?.status)) fail(400, '상태 값이 올바르지 않아요.');
    r.status = body.status;
    return reservationView(r);
  }],
  ['POST', '/admin/reservations/:id/cancel', (p) => {
    requireAdmin();
    const r = reservations.find((x) => x.id === p.id);
    if (!r) fail(404, '예약을 찾을 수 없어요.');
    if (r.status !== 'RESERVED') fail(409, '예약 완료 상태만 취소할 수 있어요.');
    r.status = 'CANCELED';
    const s = slots.find((x) => x.id === r.slotId);
    if (s) s.reservedCount -= 1;
    return reservationView(r);
  }],
  ['GET', '/admin/members', (p, q) => {
    requireAdmin();
    const keyword = (q.get('q') || '').toLowerCase();
    const status = q.get('status');
    const role = q.get('role');
    return members
      .filter((m) => (!status || m.status === status) && (!role || m.role === role))
      .filter((m) => !keyword || m.nickname.toLowerCase().includes(keyword) || m.email.toLowerCase().includes(keyword))
      .sort((a, b) => b.id - a.id)
      .map((m) => ({
        ...memberView(m),
        ticketCount: tickets.filter((t) => t.memberId === m.id && ['UNUSED', 'IN_USE'].includes(t.status)).length,
        reservationCount: reservations.filter((r) => r.memberId === m.id).length,
      }));
  }],
  ['PATCH', '/admin/members/:id', (p, q, body) => {
    requireAdmin();
    const m = members.find((x) => x.id === Number(p.id));
    if (!m) fail(404, '회원을 찾을 수 없어요.');
    if (!['ACTIVE', 'INACTIVE', 'SUSPENDED'].includes(body?.status)) fail(400, '상태 값이 올바르지 않아요.');
    m.status = body.status;
    return memberView(m);
  }],
];

// '/attractions/:id' 같은 모양을 정규식으로 바꿔서 실제 주소와 맞춰 봅니다.
const compiled = routes.map(([method, pattern, handler]) => {
  const keys = [];
  const regex = new RegExp(`^${pattern.replace(/:([a-zA-Z]+)/g, (_, k) => { keys.push(k); return '([^/]+)'; })}$`);
  return { method, regex, keys, handler };
});

export async function mockRequest(method, path, body) {
  await new Promise((r) => setTimeout(r, 150)); // 진짜 서버처럼 살짝 기다리기
  const url = new URL(path, 'http://mock');
  for (const route of compiled) {
    if (route.method !== method) continue;
    const match = url.pathname.match(route.regex);
    if (!match) continue;
    const params = Object.fromEntries(route.keys.map((k, i) => [k, decodeURIComponent(match[i + 1])]));
    const result = route.handler(params, url.searchParams, body);
    return result === null ? null : structuredClone(result); // 복사본을 돌려줘서 가짜 DB가 실수로 바뀌지 않게
  }
  throw new ApiError(`가짜 서버에 없는 주소예요: ${method} ${path}`, 404);
}
