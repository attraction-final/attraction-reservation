import { useState } from 'react';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge, Empty, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi } from '../../api/index.js';
import { RESERVATION_STATUS, shortDate } from '../../api/labels.js';

// 관리자 취소 사유 (백엔드 ReservationCancelReason). 회원 탈퇴 사유는 시스템이 쓰는 거라 뺐어요.
const CANCEL_REASONS = [
  { code: 'OPERATIONAL_ISSUE', label: '운영 사정' },
  { code: 'ATTRACTION_MAINTENANCE', label: '어트랙션 점검' },
  { code: 'WEATHER_CONDITION', label: '기상 악화' },
  { code: 'SAFETY_CONCERN', label: '안전상의 이유' },
  { code: 'USER_REQUEST', label: '이용자 요청' },
  { code: 'OTHER', label: '기타' },
];

const FILTERS = [{ code: '', label: '전체' }, ...Object.entries(RESERVATION_STATUS).map(([code, v]) => ({ code, label: v.label }))];

export default function AdminReservations() {
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState(''); // 검색 버튼(엔터)을 눌렀을 때만 반영
  const [status, setStatus] = useState('');
  const { data, loading, error, reload } = useApi(() => adminApi.reservations({ q: query, status }), [query, status]);

  const [openId, setOpenId] = useState(null); // 펼쳐 둔 카드
  const [cancelReason, setCancelReason] = useState({}); // 카드별로 고른 취소 사유
  const [nextStatus, setNextStatus] = useState({}); // 카드별로 고른 상태 값
  const [message, setMessage] = useState('');

  async function run(action, okText) {
    setMessage('');
    try {
      await action();
      setMessage(okText);
      reload();
    } catch (e) {
      setMessage(e.message);
    }
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="예약 관리" right={<span className="small muted">검색 결과 {data?.length ?? 0}건</span>}>
          <form className="row gap-8" onSubmit={(e) => { e.preventDefault(); setQuery(keyword); }}>
            <div className="search grow" style={{ height: 44, borderRadius: 14, padding: '0 12px' }}>
              <Icon name="search" size={18} strokeWidth={2} color="var(--muted)" />
              <input type="search" aria-label="닉네임, 이메일, 예약번호 검색" placeholder="닉네임 · 이메일 · 예약번호" value={keyword} onChange={(e) => setKeyword(e.target.value)} style={{ fontSize: 14 }} />
            </div>
            <button type="submit" className="btn btn-sm btn-dark">검색</button>
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
          {data?.length === 0 && <Empty>조건에 맞는 예약이 없어요.</Empty>}

          {data?.map((r) => {
            const open = r.id === openId;
            return (
              <div key={r.id} style={{ borderRadius: 20, overflow: 'hidden', background: '#fff', ...(open ? { border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' } : { border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                <button type="button" aria-expanded={open} onClick={() => setOpenId(open ? null : r.id)} className="row gap-12"
                  style={{ width: '100%', padding: 12, border: 0, background: 'transparent', textAlign: 'left' }}>
                  <img src={r.image} alt="" style={{ width: 48, height: 48, borderRadius: 14, objectFit: 'cover' }} />
                  <span className="stack grow" style={{ gap: 2 }}>
                    <span className="row between gap-8">
                      <span className="bold" style={{ fontSize: 14 }}>{r.number}</span>
                      <Badge status={RESERVATION_STATUS[r.status]} small />
                    </span>
                    <span style={{ fontSize: 13 }}>{r.attractionName} · {shortDate(r.date)} {r.time}</span>
                    <span className="small muted">{r.memberNickname}</span>
                  </span>
                </button>
                {open && (
                  <div className="stack gap-10" style={{ borderTop: '1.5px dashed var(--line)', margin: '0 12px', padding: '12px 0' }}>
                    <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 12px', fontSize: 13 }}>
                      <dt className="muted">회원</dt><dd style={{ margin: 0, textAlign: 'right' }}>{r.memberNickname} ({r.memberEmail})</dd>
                      <dt className="muted">슬롯</dt><dd style={{ margin: 0, textAlign: 'right' }}>{r.date} {r.time}~{r.endTime}</dd>
                      <dt className="muted">예약 일시</dt><dd style={{ margin: 0, textAlign: 'right' }}>{r.createdAt}</dd>
                    </dl>
                    <div className="row gap-8">
                      <select aria-label="상태 변경" className="input" style={{ height: 46, borderRadius: 12, fontSize: 14, flex: 1 }}
                        value={nextStatus[r.id] ?? r.status} onChange={(e) => setNextStatus({ ...nextStatus, [r.id]: e.target.value })}>
                        <option value="RESERVED">예약 완료</option>
                        <option value="USED">이용 완료</option>
                        <option value="NO_SHOW">노쇼</option>
                        {r.status === 'CANCELED' && <option value="CANCELED" disabled>예약 취소</option>}
                      </select>
                      <button type="button" className="btn btn-primary btn-sm" style={{ height: 46, boxShadow: '0 3px 0 var(--blue-dark)' }}
                        disabled={r.status !== 'RESERVED'} // 백엔드 규칙: 예약 완료 상태에서만 변경 가능
                        onClick={() => run(() => adminApi.updateReservation(r.id, nextStatus[r.id] ?? r.status), '상태를 저장했어요.')}>
                        상태 저장
                      </button>
                    </div>
                    <select aria-label="취소 사유" className="input" style={{ height: 46, borderRadius: 12, fontSize: 14 }} disabled={r.status !== 'RESERVED'}
                      value={cancelReason[r.id] ?? 'OPERATIONAL_ISSUE'} onChange={(e) => setCancelReason({ ...cancelReason, [r.id]: e.target.value })}>
                      {CANCEL_REASONS.map((c) => <option key={c.code} value={c.code}>취소 사유: {c.label}</option>)}
                    </select>
                    <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }} disabled={r.status !== 'RESERVED'}
                      onClick={() => window.confirm(`${r.number} 예약을 취소할까요?`) && run(() => adminApi.cancelReservation(r.id, cancelReason[r.id] ?? 'OPERATIONAL_ISSUE'), '예약을 취소했어요.')}>
                      예약 취소 처리
                    </button>
                    <span className="small muted">취소 시 슬롯 잔여 정원이 복구됩니다.</span>
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
