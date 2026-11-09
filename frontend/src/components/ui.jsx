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
  return (
    <div className="back-header">
      <BackButton to={to} />
      {/* Jua 글꼴은 가운데 정렬해도 글자가 살짝 위로 떠 보여서 2px 내려 화살표와 맞춰요. */}
      <h1 className="display" style={{ fontSize: 22, position: 'relative', top: 2 }}>{title}</h1>
    </div>
  );
}

// 뒤로가기 화살표 버튼만 따로 쓰고 싶을 때 (예: 아래 탭바가 있는 화면의 제목 옆)
// to 가 있으면 그 주소로, 없으면 브라우저의 "이전 화면"으로 가요.
export function BackButton({ to, style }) {
  const navigate = useNavigate();
  return (
    <button type="button" className="icon-btn" aria-label="뒤로 가기" style={style} onClick={() => (to ? navigate(to) : navigate(-1))}>
      <Icon name="back" strokeWidth={2.2} />
    </button>
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

// ───────── 어트랙션 질문 버튼 (화면 오른쪽 아래에 떠 있는 동그란 버튼) ─────────
// 사용법: 화면의 <TabBar /> 바로 위에 <AskFab /> 한 줄만 넣으면 돼요.
//  - .screen 이 position: relative 라서, 그 안에서 오른쪽 아래에 붙어요.
//  - bottom 92px = 탭바 높이 76px + 띄우는 간격 16px
//  - z-index 5 : 예약 취소 같은 아래 시트(.sheet-backdrop, z-index 10)보다는 아래에 있어야 해요.
export function AskFab() {
  return (
    <Link to="/attractions/ask" aria-label="어트랙션에 물어보기" title="어트랙션에 물어보기"
      style={{
        position: 'absolute', right: 16, bottom: 92, zIndex: 5,
        width: 56, height: 56, borderRadius: '50%',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'var(--blue)', color: '#fff',
        boxShadow: '0 6px 16px rgba(47, 111, 219, 0.35)',
      }}>
      <Icon name="chat" size={26} strokeWidth={2} />
    </Link>
  );
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
  { to: '/admin/entry', label: '입장', icon: 'check' },
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
        {/* 관리자 화면에 들어온 입구(마이페이지)로 돌아가는 버튼. marginLeft: 'auto' 가 남는 공간을 왼쪽에 몰아 줘서 오른쪽 끝에 붙어요. */}
        <Link to="/my" className="row gap-4" style={{ marginLeft: 'auto', height: 34, padding: '0 12px', borderRadius: 999, background: 'var(--soft)', color: 'var(--blue-ink)', fontSize: 13, fontWeight: 700, textDecoration: 'none' }}>
          <Icon name="user" size={16} strokeWidth={2} />
          마이페이지
        </Link>
      </div>
      <div className="row between" style={{ alignItems: 'baseline' }}>
        <h1 className="display" style={{ fontSize: 24 }}>{title}</h1>
        {right}
      </div>
      {children}
    </header>
  );
}
