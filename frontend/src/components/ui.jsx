import { Link, NavLink, useNavigate } from 'react-router-dom';
import Icon from './Icon.jsx';
import { SERVICE_NAME } from '../config.js';

/* 여러 화면에서 같이 쓰는 작은 부품들을 모아 둔 파일입니다. */

// 상태 배지: <Badge status={RESERVATION_STATUS.RESERVED} />  또는 <Badge tone="blue">글자</Badge>
export function Badge({ status, tone, small, children, style }) {
  const t = tone || status?.tone || 'gray';
  return (
    <span className={`badge tone-${t}${small ? ' sm' : ''}`} style={style}>
      {children || status?.label}
    </span>
  );
}

// 뒤로가기 버튼 + 제목이 있는 위쪽 줄
export function BackHeader({ title, to }) {
  const navigate = useNavigate();
  return (
    <div className="back-header">
      <button type="button" className="icon-btn" aria-label="뒤로 가기" onClick={() => (to ? navigate(to) : navigate(-1))}>
        <Icon name="back" strokeWidth={2.2} />
      </button>
      <h1 className="display" style={{ fontSize: 22 }}>{title}</h1>
    </div>
  );
}

// 사진 위에 떠 있는 동그란 뒤로가기 버튼
export function FloatingBack({ to }) {
  const navigate = useNavigate();
  return (
    <button type="button" className="icon-btn floating" aria-label="뒤로 가기" onClick={() => (to ? navigate(to) : navigate(-1))}>
      <Icon name="back" size={20} strokeWidth={2.2} />
    </button>
  );
}

// 불러오는 중 / 오류 / 비어 있음 안내
export function Loading() {
  return <div className="state-box">불러오는 중이에요…</div>;
}
export function ErrorBox({ error, onRetry }) {
  return (
    <div className="state-box">
      <p style={{ margin: '0 0 10px' }}>{error?.message || '문제가 생겼어요.'}</p>
      {error?.status === 401
        ? <Link to="/login">로그인하러 가기</Link>
        : onRetry && <button type="button" className="chip" onClick={onRetry}>다시 시도</button>}
    </div>
  );
}
export function Empty({ children }) {
  return <div className="state-box">{children}</div>;
}

// ───────── 하단 탭바 ─────────
const USER_TABS = [
  { to: '/', label: '홈', icon: 'home', end: true },
  { to: '/attractions', label: '어트랙션', icon: 'ferris' },
  { to: '/my/reservations', label: '예약', icon: 'calendar' },
  { to: '/my/tickets', label: '입장권', icon: 'ticket' },
  { to: '/my', label: '마이', icon: 'user', end: true },
];
const ADMIN_TABS = [
  { to: '/admin/members', label: '회원', icon: 'users' },
  { to: '/admin/slots', label: '어트랙션', icon: 'ferris' },
  { to: '/admin/reservations', label: '예약', icon: 'calendar' },
  { to: '/admin/products', label: '입장권 상품', icon: 'ticket' },
  { to: '/admin/orders', label: '주문 · 환불', icon: 'receipt' },
];

function Tabs({ tabs, label }) {
  return (
    <nav className="tabbar" aria-label={label}>
      {tabs.map((t) =>
        t.disabled ? (
          // 아직 화면이 없는 메뉴: 눌러도 이동하지 않아요
          <span key={t.to} className="tab" title="준비 중" aria-disabled="true">
            <span className="tab-pill"><Icon name={t.icon} /></span>{t.label}
          </span>
        ) : (
          <NavLink key={t.to} to={t.to} end={t.end} className={({ isActive }) => `tab${isActive ? ' active' : ''}`}>
            <span className="tab-pill"><Icon name={t.icon} /></span>{t.label}
          </NavLink>
        )
      )}
    </nav>
  );
}
export const TabBar = () => <Tabs tabs={USER_TABS} label="하단 메뉴" />;
export const AdminTabBar = () => <Tabs tabs={ADMIN_TABS} label="관리자 메뉴" />;

// 관리자 화면 위쪽 공통 헤더 (로고 + 서비스 이름 + 관리자 배지)
export function AdminHeader({ title, right, children }) {
  return (
    <header className="card" style={{ borderRadius: '0 0 26px 26px', padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 12, flexShrink: 0 }}>
      <div className="row gap-10">
        <img src="/images/logo.svg" alt="" width={36} height={36} style={{ borderRadius: 10 }} />
        <span className="display" style={{ fontSize: 18 }}>{SERVICE_NAME}</span>
        <Badge tone="yellow" small>관리자</Badge>
      </div>
      <div className="row between" style={{ alignItems: 'baseline' }}>
        <h1 className="display" style={{ fontSize: 24 }}>{title}</h1>
        {right}
      </div>
      {children}
    </header>
  );
}
