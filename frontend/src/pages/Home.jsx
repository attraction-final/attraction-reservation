import { Link } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { AskFab, Badge, TabBar } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { attractionApi, parkApi, reservationApi, ticketApi } from '../api/index.js';
import { TICKET_STATUS, TICKET_TYPE, formatDate } from '../api/labels.js';
import { useAuth } from '../context/AuthContext.jsx';
import { SERVICE_NAME } from '../config.js';

// 빠른 메뉴 4칸
const QUICK_MENUS = [
  { to: '/tickets', label: '입장권 구매', icon: 'ticketPlus', primary: true },
  { to: '/route', label: '루트 추천', icon: 'map', bg: 'var(--pink-soft)', color: 'var(--pink-dark)' },
  { to: '/attractions', label: '어트랙션 예약', icon: 'ferrisFull', bg: 'var(--yellow-soft)', color: 'var(--yellow-ink)' },
  { to: '/my/reservations', label: '내 예약', icon: 'calendarCheck', bg: 'var(--green-soft)', color: 'var(--green-ink)' },
];

export default function Home() {
  const { user } = useAuth();
  // 화면에 필요한 데이터를 서버에서 각각 불러와요.
  const park = useApi(() => parkApi.today(), []);
  const rides = useApi(() => attractionApi.list('AVAILABLE'), []);
  // 로그인한 경우에만 내 입장권/예약을 불러와요. (비로그인이면 빈 배열)
  const tickets = useApi(() => (user ? ticketApi.mine('USABLE') : Promise.resolve([])), [user]);
  const reservations = useApi(() => (user ? reservationApi.mine('UPCOMING') : Promise.resolve([])), [user]);

  const myTicket = tickets.data?.[0];
  const nextReservation = reservations.data?.[0];

  return (
    <div className="screen">
      <div className="screen-body">
        {/* 1. 위쪽 큰 사진 + 로고 + 로그인 버튼 */}
        <div className="hero" style={{ height: 300 }}>
          <img src="/images/park.jpg" alt="성, 롤러코스터, 대관람차, 회전목마가 보이는 테마파크 전경" style={{ objectPosition: '40% center' }} />
          <div className="row between" style={{ position: 'absolute', top: 14, left: 16, right: 16 }}>
            <div className="pill-white row gap-8" style={{ padding: '6px 14px 6px 8px' }}>
              {/* 관리자 화면(주문 · 환불 등)과 같은 대관람차 로고를 써요 */}
              <img src="/images/logo.svg" alt="" width={30} height={30} style={{ borderRadius: 9 }} />
              <span className="display" style={{ fontSize: 20 }}>{SERVICE_NAME}</span>
            </div>
            {user ? (
              <Link to="/my" className="pill-white row" style={{ height: 42, padding: '0 16px', fontSize: 14, fontWeight: 700, color: 'var(--blue-ink)', textDecoration: 'none' }}>
                {/* 글꼴이 달라서(놀이동선=Jua, 이 글자=Noto Sans KR) 같은 가운데 정렬이어도 글자가 2px쯤 낮아 보여요. 살짝 올려서 맞춰요. */}
                <span style={{ position: 'relative', top: -1 }}>{user.nickname}님</span>
              </Link>
            ) : (
              <Link to="/login" className="pill-white row" style={{ height: 42, padding: '0 16px', fontSize: 14, fontWeight: 700, color: 'var(--blue-ink)', textDecoration: 'none' }}>
                <span style={{ position: 'relative', top: -2 }}>로그인</span>
              </Link>
            )}
          </div>
        </div>

        {/* 2. 오늘 운영 시간 (사진 위로 살짝 겹쳐 올라온 카드) */}
        <div className="card row between" style={{ margin: '-44px 16px 0', position: 'relative', borderRadius: 22, padding: '16px 18px', boxShadow: 'var(--shadow-strong)' }}>
          <div className="stack" style={{ gap: 2 }}>
            <span className="small muted">오늘 운영 시간</span>
            <span className="display" style={{ fontSize: 28, lineHeight: 1.15 }}>
              {park.data ? `${park.data.openTime} – ${park.data.closeTime}` : '–'}
            </span>
          </div>
          <div className="stack" style={{ gap: 6, alignItems: 'flex-end' }}>
            <Badge tone="green">운영 중 {park.data?.operatingCount ?? '-'}</Badge>
            <Badge tone="gray">점검 {park.data?.maintenanceCount ?? '-'}</Badge>
          </div>
        </div>

        {/* 3. 내 입장권 */}
        <Link to={user ? '/my/tickets' : '/login'} className="card card-link row gap-12" style={{ margin: '14px 16px 0', padding: '14px 16px' }}>
          <span className="icon-circle" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--yellow-soft)', color: 'var(--yellow-ink)' }}>
            <Icon name="ticket" size={24} strokeWidth={1.8} />
          </span>
          <span className="stack grow" style={{ gap: 2 }}>
            <span className="bold" style={{ fontSize: 15 }}>내 입장권</span>
            <span className="muted" style={{ fontSize: 13 }}>
              {!user ? '로그인하고 입장권을 확인하세요'
                : myTicket ? `${TICKET_TYPE[myTicket.type]} · ${formatDate(myTicket.date, false)}`
                : '사용 가능한 입장권이 없어요'}
            </span>
          </span>
          {myTicket && <Badge status={TICKET_STATUS[myTicket.status]} />}
          <Icon name="chevron" size={18} strokeWidth={2} color="#8A94AB" />
        </Link>

        {/* 4. 빠른 메뉴 4칸 */}
        <div style={{ margin: '14px 16px 0', display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 10 }}>
          {QUICK_MENUS.map((m) => (
            <Link key={m.to} to={m.to} className={m.primary ? '' : 'card card-link'}
              style={{
                borderRadius: 18, padding: '12px 4px 10px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, textDecoration: 'none',
                ...(m.primary ? { background: 'var(--blue)', color: '#fff', boxShadow: '0 4px 0 var(--blue-dark)' } : {}),
              }}>
              <span className="icon-circle" style={{ width: 40, height: 40, background: m.primary ? 'rgba(255,255,255,0.2)' : m.bg, color: m.primary ? '#fff' : m.color }}>
                <Icon name={m.icon} strokeWidth={1.8} />
              </span>
              <span className="bold" style={{ fontSize: 11.5, whiteSpace: 'nowrap' }}>{m.label}</span>
            </Link>
          ))}
        </div>

        {/* 5. 다음 예약 */}
        {nextReservation && (
          <div className="stack gap-8" style={{ margin: '18px 16px 0' }}>
            <h2 className="h2">다음 예약</h2>
            <Link to="/my/reservations" className="card card-link row gap-12" style={{ padding: '12px 14px' }}>
              <span className="display icon-circle" style={{ width: 64, height: 48, borderRadius: 14, background: 'var(--blue-soft)', color: 'var(--blue-ink)', fontSize: 20 }}>
                {nextReservation.time}
              </span>
              <span className="stack grow" style={{ gap: 2 }}>
                <span className="bold" style={{ fontSize: 15 }}>{nextReservation.attractionName}</span>
                <span className="small muted">예약번호 {nextReservation.number ?? nextReservation.id} · {nextReservation.zone}</span>
              </span>
              <Icon name="chevron" size={18} strokeWidth={2} color="#8A94AB" />
            </Link>
          </div>
        )}

        {/* 6. 지금 예약 가능한 어트랙션 (옆으로 넘기는 카드) */}
        <div className="stack gap-8" style={{ margin: '18px 0 0 16px' }}>
          <div className="row between" style={{ paddingRight: 16 }}>
            <h2 className="h2">지금 예약 가능한 어트랙션</h2>
            <Link to="/attractions" className="bold" style={{ fontSize: 13, textDecoration: 'none' }}>전체</Link>
          </div>
          {/* paddingRight 16 = 왼쪽 여백(16)과 맞춤. 동그란 버튼은 카드보다 아래에 있어서 따로 비우지 않아요 */}
          <div className="row gap-12" style={{ overflowX: 'auto', paddingBottom: 6, paddingRight: 16, alignItems: 'stretch' }}>
            {(rides.data || []).map((a) => (
              <Link key={a.id} to={`/attractions/${a.id}`} className="card card-link" style={{ width: 150, flexShrink: 0, borderRadius: 18, overflow: 'hidden' }}>
                <img src={a.image} alt={a.name} style={{ width: '100%', height: 96, objectFit: 'cover' }} />
                <span className="stack" style={{ gap: 2, padding: '10px 12px 12px' }}>
                  <span className="bold" style={{ fontSize: 14 }}>{a.name}</span>
                  {a.nextSlot && (
                    <span className="small" style={{ color: 'var(--blue-ink)', fontWeight: 500 }}>
                      {a.nextSlot.time} · 잔여 {a.nextSlot.remaining}석
                    </span>
                  )}
                </span>
              </Link>
            ))}
          </div>
        </div>
        {/* 오른쪽 아래 동그란 버튼에 마지막 내용이 가리지 않게 빈 공간을 둬요 */}
        <div style={{ height: 72 }} />
      </div>
      <AskFab />
      <TabBar />
    </div>
  );
}
