import { useState } from 'react';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge, Empty, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi } from '../../api/index.js';
import { MEMBER_STATUS, formatDate } from '../../api/labels.js';

const FILTERS = [{ code: '', label: '전체' }, ...Object.entries(MEMBER_STATUS).map(([code, v]) => ({ code, label: v.label }))];

export default function AdminMembers() {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [role, setRole] = useState('');
  const { data, loading, error, reload } = useApi(() => adminApi.members({ q: query, status, role }), [query, status, role]);

  const [openId, setOpenId] = useState(null);
  const [nextStatus, setNextStatus] = useState({});
  const [message, setMessage] = useState('');

  async function save(m) {
    setMessage('');
    try {
      await adminApi.updateMember(m.id, nextStatus[m.id] ?? m.status);
      setMessage(`${m.nickname} 회원 상태를 저장했어요.`);
      reload();
    } catch (e) {
      setMessage(e.message);
    }
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="회원 관리" right={<span className="small muted">전체 {data?.length ?? 0}명</span>}>
          <form className="row gap-8" onSubmit={(e) => { e.preventDefault(); setQuery(keyword); }}>
            <div className="search grow" style={{ height: 44, borderRadius: 14, padding: '0 12px' }}>
              <Icon name="search" size={18} strokeWidth={2} color="var(--muted)" />
              <input type="search" aria-label="닉네임 또는 이메일 검색" placeholder="닉네임 또는 이메일" value={keyword} onChange={(e) => setKeyword(e.target.value)} style={{ fontSize: 14 }} />
            </div>
            <select aria-label="권한 필터" value={role} onChange={(e) => setRole(e.target.value)}
              style={{ height: 44, borderRadius: 14, border: '1.5px solid var(--line)', background: '#fff', padding: '0 8px', fontSize: 13 }}>
              <option value="">권한 전체</option>
              <option value="USER">일반</option>
              <option value="ADMIN">관리자</option>
            </select>
          </form>
          <div className="row gap-8" style={{ overflowX: 'auto' }}>
            {FILTERS.map((f) => (
              <button key={f.code || 'all'} type="button" className={`chip${status === f.code ? ' on' : ''}`} onClick={() => setStatus(f.code)} style={{ height: 34, padding: '0 12px', fontSize: 12 }}>{f.label}</button>
            ))}
          </div>
        </AdminHeader>

        <div className="pad stack gap-10" style={{ paddingTop: 14 }}>
          {message && <div className="toast">{message}</div>}
          {loading && <Loading />}
          {error && <ErrorBox error={error} onRetry={reload} />}
          {data?.length === 0 && <Empty>조건에 맞는 회원이 없어요.</Empty>}

          {data?.map((m) => {
            const open = m.id === openId;
            const gone = m.status === 'WITHDRAWN';
            return (
              <div key={m.id} style={{ borderRadius: 20, overflow: 'hidden', ...(open ? { background: '#fff', border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' } : gone ? { background: 'rgba(255,255,255,0.6)', border: '2px solid #DCE5F0' } : { background: '#fff', border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                <button type="button" aria-expanded={open} onClick={() => setOpenId(open ? null : m.id)} className="row gap-12"
                  style={{ width: '100%', padding: 12, border: 0, background: 'transparent', textAlign: 'left' }}>
                  <span className="display icon-circle" style={{ width: 44, height: 44, fontSize: 18, ...(gone ? { background: 'var(--gray-soft)', color: 'var(--subtle)' } : { background: 'var(--yellow-soft)', color: 'var(--yellow-ink)', boxShadow: '0 0 0 2px var(--yellow)' }) }}>
                    {m.nickname.slice(0, 1)}
                  </span>
                  <span className="stack grow" style={{ gap: 2 }}>
                    <span className="row gap-8">
                      <span className="bold" style={{ fontSize: 15, color: gone ? 'var(--muted)' : 'var(--ink)' }}>{m.nickname}</span>
                      {m.role === 'ADMIN' && <Badge tone="yellow" small>관리자</Badge>}
                    </span>
                    <span className="small muted">#{m.id} · {m.email}</span>
                  </span>
                  <Badge status={MEMBER_STATUS[m.status]} small />
                </button>
                {open && (
                  <div className="stack gap-10" style={{ borderTop: '1.5px dashed var(--line)', margin: '0 12px', padding: '12px 0' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }}>
                      {/* 회원 목록 응답에 입장권·예약 수가 없어서 값이 없으면 '-' 로 보여요 */}
                      <Stat label="보유 입장권" value={m.ticketCount == null ? '-' : `${m.ticketCount}매`} />
                      <Stat label="예약" value={m.reservationCount == null ? '-' : `${m.reservationCount}건`} />
                      <Stat label="가입일" value={formatDate(m.joinedAt, false)} smallText />
                    </div>
                    <div className="row gap-8">
                      <select aria-label="상태 변경" className="input" style={{ height: 46, borderRadius: 12, fontSize: 14, flex: 1 }} disabled={gone}
                        value={nextStatus[m.id] ?? m.status} onChange={(e) => setNextStatus({ ...nextStatus, [m.id]: e.target.value })}>
                        <option value="ACTIVE">활성</option>
                        <option value="INACTIVE">비활성</option>
                        <option value="SUSPENDED">정지</option>
                        {gone && <option value="WITHDRAWN">탈퇴</option>}
                      </select>
                      <button type="button" className="btn btn-primary btn-sm" style={{ height: 46, padding: '0 22px', boxShadow: '0 3px 0 var(--blue-dark)' }} disabled={gone} onClick={() => save(m)}>저장</button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}

function Stat({ label, value, smallText }) {
  return (
    <div className="stack" style={{ alignItems: 'center', gap: 2, background: '#F3F7FC', borderRadius: 14, padding: 8 }}>
      <span style={{ fontSize: 11, color: 'var(--muted)' }}>{label}</span>
      <span className={smallText ? 'bold' : 'display'} style={{ fontSize: smallText ? 12 : 17, paddingTop: smallText ? 3 : 0 }}>{value}</span>
    </div>
  );
}
