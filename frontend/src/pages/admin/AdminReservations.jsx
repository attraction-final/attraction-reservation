import { useEffect, useRef, useState } from 'react';
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

const PAGE_SIZE = 20; // 한 번에 불러올 예약 수

const FILTERS = [{ code: '', label: '전체' }, ...Object.entries(RESERVATION_STATUS).map(([code, v]) => ({ code, label: v.label }))];

export default function AdminReservations() {
  // ── 필터 (모두 서버로 보내요) ──
  const [status, setStatus] = useState('');
  const [attractionId, setAttractionId] = useState('');
  const [userId, setUserId] = useState('');

  // 필터 선택지 + 번호 → 이름 찾기 표 (처음에 한 번만)
  const options = useApi(() => adminApi.reservationFilterOptions(), []);
  const lookup = options.data;

  // ── 목록 (페이지를 이어 붙여요) ──
  const [items, setItems] = useState([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const requestId = useRef(0); // 늦게 온 옛날 응답을 무시하려는 번호표

  const [openId, setOpenId] = useState(null); // 펼쳐 둔 카드
  const [details, setDetails] = useState({}); // 펼쳐서 불러온 상세 { [예약id]: 상세 }
  const [cancelReason, setCancelReason] = useState({}); // 카드별로 고른 취소 사유
  const [nextStatus, setNextStatus] = useState({}); // 카드별로 고른 상태 값
  const [message, setMessage] = useState('');

  async function load(nextPage, append) {
    if (!lookup) return;
    const myId = ++requestId.current;
    setLoading(true);
    setError(null);
    try {
      const res = await adminApi.reservations({ page: nextPage, size: PAGE_SIZE, status, attractionId, userId }, lookup);
      if (myId !== requestId.current) return;
      setItems((prev) => (append ? [...prev, ...res.items] : res.items));
      setPage(res.page);
      setTotal(res.totalElements);
      setHasNext(res.hasNext);
    } catch (e) {
      if (myId === requestId.current) setError(e);
    } finally {
      if (myId === requestId.current) setLoading(false);
    }
  }

  // 필터가 바뀌면(또는 선택지를 다 불러오면) 첫 페이지부터 다시
  useEffect(() => {
    setOpenId(null);
    load(0, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lookup, status, attractionId, userId]);

  // 카드를 펼칠 때 상세를 불러와요. (한 번 불러온 건 다시 안 불러와요)
  async function toggle(id) {
    if (openId === id) { setOpenId(null); return; }
    setOpenId(id);
    if (details[id]) return;
    try {
      const d = await adminApi.reservationDetail(id, lookup);
      setDetails((prev) => ({ ...prev, [id]: d }));
    } catch (e) {
      setMessage(e.message);
    }
  }

  // 상태 변경·취소 후: 목록 전체를 다시 부르지 않고, "그 예약 한 건"만 상세로 다시 불러와서 바꿔 끼워요.
  // (여러 페이지를 불러 둔 상태여도 스크롤 위치와 목록이 유지돼요)
  async function run(id, action, okText) {
    setMessage('');
    try {
      await action();
      const d = await adminApi.reservationDetail(id, lookup);
      setDetails((prev) => ({ ...prev, [id]: d }));
      setItems((prev) => prev.map((r) => (r.id === id ? { ...r, ...d } : r)));
      setMessage(okText);
    } catch (e) {
      setMessage(e.message);
    }
  }

  const selectStyle = { height: 40, borderRadius: 12, fontSize: 13, flex: 1, minWidth: 0 };

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="예약 관리" right={<span className="small muted">검색 결과 {total}건</span>}>
          {/* 회원·어트랙션 필터: 고르면 바로 서버에서 다시 불러와요 */}
          <div className="row gap-8">
            <select aria-label="회원 필터" className="input" style={selectStyle} value={userId}
              onChange={(e) => setUserId(e.target.value)} disabled={!lookup}>
              <option value="">전체 회원</option>
              {lookup?.members.map((m) => <option key={m.id} value={m.id}>{m.nickname} ({m.email})</option>)}
            </select>
            <select aria-label="어트랙션 필터" className="input" style={selectStyle} value={attractionId}
              onChange={(e) => setAttractionId(e.target.value)} disabled={!lookup}>
              <option value="">전체 어트랙션</option>
              {lookup?.attractions.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </div>
          <div className="row gap-8" style={{ overflowX: 'auto' }}>
            {FILTERS.map((f) => (
              <button key={f.code || 'all'} type="button" className={`chip${status === f.code ? ' on' : ''}`} onClick={() => setStatus(f.code)} style={{ height: 34, padding: '0 12px', fontSize: 12 }}>{f.label}</button>
            ))}
          </div>
        </AdminHeader>

        <div className="pad stack gap-10" style={{ paddingTop: 14 }}>
          {message && <div className="toast">{message}</div>}
          {options.error && <ErrorBox error={options.error} onRetry={options.reload} />}
          {loading && items.length === 0 && !options.error && <Loading />}
          {error && <ErrorBox error={error} onRetry={() => load(items.length ? page + 1 : 0, items.length > 0)} />}
          {!loading && !error && items.length === 0 && <Empty>조건에 맞는 예약이 없어요.</Empty>}

          {items.map((r) => {
            const open = r.id === openId;
            const d = details[r.id]; // 펼친 뒤 불러온 상세 (아직 없으면 undefined)
            return (
              <div key={r.id} style={{ borderRadius: 20, overflow: 'hidden', background: '#fff', ...(open ? { border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' } : { border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                <button type="button" aria-expanded={open} onClick={() => toggle(r.id)} className="row gap-12"
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
                      {/* 아래는 상세 API 에만 있는 정보 */}
                      <dt className="muted">입장권</dt><dd style={{ margin: 0, textAlign: 'right' }}>{d ? `#${d.ticketId}` : '…'}</dd>
                      {r.status === 'CANCELED' && (
                        <>
                          <dt className="muted">취소 사유</dt><dd style={{ margin: 0, textAlign: 'right' }}>{d?.cancelReason ?? '…'}</dd>
                          <dt className="muted">취소 일시</dt><dd style={{ margin: 0, textAlign: 'right' }}>{d?.cancelledAt ?? '…'}</dd>
                        </>
                      )}
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
                        onClick={() => run(r.id, () => adminApi.updateReservation(r.id, nextStatus[r.id] ?? r.status), '상태를 저장했어요.')}>
                        상태 저장
                      </button>
                    </div>
                    <select aria-label="취소 사유" className="input" style={{ height: 46, borderRadius: 12, fontSize: 14 }} disabled={r.status !== 'RESERVED'}
                      value={cancelReason[r.id] ?? 'OPERATIONAL_ISSUE'} onChange={(e) => setCancelReason({ ...cancelReason, [r.id]: e.target.value })}>
                      {CANCEL_REASONS.map((c) => <option key={c.code} value={c.code}>취소 사유: {c.label}</option>)}
                    </select>
                    <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }} disabled={r.status !== 'RESERVED'}
                      onClick={() => window.confirm(`${r.number} 예약을 취소할까요?`) && run(r.id, () => adminApi.cancelReservation(r.id, cancelReason[r.id] ?? 'OPERATIONAL_ISSUE'), '예약을 취소했어요.')}>
                      예약 취소 처리
                    </button>
                    <span className="small muted">취소 시 슬롯 잔여 정원이 복구됩니다.</span>
                  </div>
                )}
              </div>
            );
          })}

          {hasNext && !error && (
            <button type="button" className="btn btn-outline" style={{ width: '100%' }} disabled={loading} onClick={() => load(page + 1, true)}>
              {loading ? '불러오는 중…' : `더 보기 (${items.length} / ${total})`}
            </button>
          )}
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}
