/*
 * 서버는 상태를 영어 코드(예: RESERVED)로 보내고,
 * 화면은 그 코드를 한국어 글자 + 배지 색(tone)으로 바꿔서 보여 줍니다.
 * 스프링부트의 enum 이름과 똑같이 맞추면 돼요.
 */

export const ATTRACTION_STATUS = {
  OPERATING: { label: '운영중', tone: 'green' },
  SOLD_OUT: { label: '예약 마감', tone: 'pink' },     // 운영 중이지만 오늘 슬롯이 다 찬 상태(서버가 soldOut=true 로 알려 줌)
  MAINTENANCE: { label: '점검', tone: 'gray' },
  SUSPENDED: { label: '일시중단', tone: 'orange' },
};

export const RESERVATION_STATUS = {
  RESERVED: { label: '예약 완료', tone: 'blue' },
  USED: { label: '이용 완료', tone: 'gray' },
  CANCELED: { label: '예약 취소', tone: 'orange' },
  NO_SHOW: { label: '노쇼', tone: 'dashed' },
};

export const TICKET_STATUS = {
  UNUSED: { label: '미사용', tone: 'blue' },
  IN_USE: { label: '사용중', tone: 'solid-blue' },
  USED: { label: '사용 완료', tone: 'gray' },
  REFUNDED: { label: '환불', tone: 'orange' },
  EXPIRED: { label: '만료', tone: 'dashed' },
};

export const TICKET_TYPE = {
  FULL_DAY: '종일권',
  AFTERNOON: '오후권',
};

export const ORDER_STATUS = {
  // 백엔드 OrderStatus enum 과 똑같은 이름이에요.
  PENDING: { label: '결제 대기', tone: 'yellow' },
  COMPLETED: { label: '결제 완료', tone: 'blue' },
  PARTIALLY_REFUNDED: { label: '부분 환불', tone: 'orange' },
  REFUNDED: { label: '환불 완료', tone: 'orange' },
  CANCELLED: { label: '주문 취소', tone: 'orange' },

};

// 입장권 상품 판매 상태 (백엔드 TicketProductStatus)
// ON_SALE 만 살 수 있어요. 나머지는 구매 화면에서 회색으로 막아요.
// DELETED 는 서버가 목록에서 아예 빼서 보내지 않아요.
export const TICKET_PRODUCT_STATUS = {
  ON_SALE: { label: '판매 중', tone: 'green' },
  STOPPED: { label: '판매 중지', tone: 'gray' },
  ENDED: { label: '판매 종료', tone: 'gray' },
  OUT_OF_STOCK: { label: '품절', tone: 'orange' },
};

export const MEMBER_STATUS = {
  ACTIVE: { label: '활성', tone: 'green' },
  INACTIVE: { label: '비활성', tone: 'gray' },
  SUSPENDED: { label: '정지', tone: 'pink' },
  WITHDRAWN: { label: '탈퇴', tone: 'dashed' },
};

export const MEMBER_ROLE = { USER: '일반', ADMIN: '관리자' };

// 결제 화면에 보여 줄 결제 수단 버튼
// 포트원 테스트 채널에서 확인된 수단만 열어 두세요.
// 다른 수단은 포트원 payMethod 값과 채널 지원 여부를 확인한 뒤 주석을 풀면 돼요. (Checkout.jsx 의 PORTONE_PAY_METHOD 참고)
export const PAYMENT_METHODS = [
  { code: 'CARD', label: '신용/체크카드' },
  // { code: 'EASY_PAY', label: '간편결제' },
  // { code: 'BANK_TRANSFER', label: '계좌이체' },
];

// ───────── 작은 도우미 함수 ─────────
const DOW = ['일', '월', '화', '수', '목', '금', '토'];

// '2026-10-24' → '2026.10.24 (토)'
export function formatDate(iso, withDow = true) {
  if (!iso) return '';
  const d = new Date(`${iso}T00:00:00`);
  const text = iso.replaceAll('-', '.');
  return withDow ? `${text} (${DOW[d.getDay()]})` : text;
}

// '2026-10-24' → '10.24'
export function shortDate(iso) {
  return iso ? iso.slice(5).replace('-', '.') : '';
}

export function dayOfWeek(iso) {
  return DOW[new Date(`${iso}T00:00:00`).getDay()];
}

// 50000 → '50,000원' / 가격이 아직 없으면 '[가격]'
export function formatPrice(n) {
  return typeof n === 'number' ? `${n.toLocaleString('ko-KR')}원` : '[가격]';
}