import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { Badge, Empty, ErrorBox, Loading, TabBar } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { reservationApi } from '../api/index.js';
import { RESERVATION_STATUS, shortDate } from '../api/labels.js';

export default function MyReservations() {
  const { state } = useLocation(); // 예약 직후라면 { doneId: 'RSV-...' } 예약번호가 들어 있어요
  const [scope, setScope] = useState('UPCOMING'); // 'UPCOMING' 남은 예약 | 'ALL' 전체
  const { data, loading, error, reload } = useApi(() => reservationApi.mine(scope), [scope]);
  const upcomingCount = useApi(() => reservationApi.mine('UPCOMING'), [data]); // 탭 옆 숫자용

  const [target, setTarget] = useState(null); // 취소 확인창에 띄울 예약
  const [cancelError, setCancelError] = useState('');

  async function confirmCancel() {
    setCancelError('');
    try {
      await reservationApi.cancel(target.id);
      setTarget(null);
      reload();
    } catch (e) {
      setCancelError(e.message);
    }
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="card stack gap-12" style={{ borderRadius: '0 0 26px 26px', padding: '18px 16px 12px' }}>
          <div className="row between">
            <h1 className="h1">내 예약</h1>
            <Link to="/route" className="badge tone-pink" style={{ textDecoration: 'none', padding: '8px 12px', fontSize: 13 }}>루트 추천</Link>
          </div>
          <div className="segment" role="tablist">
            <button type="button" role="tab" aria-selected={scope === 'UPCOMING'} className={scope === 'UPCOMING' ? 'on' : ''} onClick={() => setScope('UPCOMING')}>
              남은 예약 ({upcomingCount.data?.length ?? 0})
            </button>
            <button type="button" role="tab" aria-selected={scope === 'ALL'} className={scope === 'ALL' ? 'on' : ''} onClick={() => setScope('ALL')}>전체</button>
          </div>
        </div>

        <div className="pad stack gap-12" style={{ paddingTop: 14 }}>
          {state?.doneId && (
            <div className="toast"><Icon name="check" size={18} strokeWidth={2.4} />예약이 완료되었습니다 · 예약번호 {state.doneId}</div>
          )}
          {loading && <Loading />}
          {error && <ErrorBox error={error} onRetry={reload} />}
          {data?.length === 0 && <Empty>예약이 없어요. 어트랙션을 둘러보세요!</Empty>}

          {data?.map((r) => (
            <div key={r.id} className="card" style={{ borderRadius: 22, overflow: 'hidden', opacity: r.status === 'RESERVED' ? 1 : 0.85 }}>
              <div className="row gap-12" style={{ padding: 12 }}>
                <img src={r.image} alt={r.attractionName} style={{ width: 60, height: 60, borderRadius: 14, objectFit: 'cover' }} />
                <div className="stack grow" style={{ gap: 2 }}>
                  <span className="bold" style={{ fontSize: 15 }}>{r.attractionName}</span>
                  {/* id 는 취소용 내부 번호, number 는 사람이 보는 예약번호(RSV-...) */}
                  <span className="small muted">{r.number ?? r.id} · 탑승 {r.rideMinutes}분 · {r.zone}</span>
                </div>
                <div className="stack" style={{ alignItems: 'flex-end', gap: 4 }}>
                  <span className="display" style={{ fontSize: 22, color: 'var(--blue-ink)' }}>{r.time}</span>
                  {scope === 'ALL' && <span className="small muted">{shortDate(r.date)}</span>}
                  <Badge status={RESERVATION_STATUS[r.status]} small />
                </div>
              </div>
              {r.status === 'RESERVED' && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', borderTop: '1px solid #EEF2F7' }}>
                  <Link to={`/attractions/${r.attractionId}`} className="row bold" style={{ height: 46, justifyContent: 'center', fontSize: 14, textDecoration: 'none', borderRight: '1px solid #EEF2F7' }}>상세 보기</Link>
                  <button type="button" onClick={() => setTarget(r)} className="bold" style={{ height: 46, border: 0, background: 'transparent', fontSize: 14, color: 'var(--orange-ink)' }}>예약 취소</button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* 아래에서 올라오는 취소 확인창 */}
      {target && (
        <div className="sheet-backdrop" onClick={() => setTarget(null)}>
          <div className="sheet" role="dialog" aria-label="예약 취소 확인" onClick={(e) => e.stopPropagation()}>
            <span className="display" style={{ fontSize: 21 }}>예약을 취소할까요?</span>
            <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
              {target.attractionName} · {target.time} · 취소 후 좌석이 다른 이용객에게 돌아갑니다. 입장권 환불은 되지 않습니다.
            </span>
            {cancelError && <span className="error-text">{cancelError}</span>}
            <div className="row gap-10" style={{ marginTop: 6 }}>
              <button type="button" onClick={() => setTarget(null)} className="bold" style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>닫기</button>
              <button type="button" onClick={confirmCancel} className="bold" style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15, boxShadow: '0 4px 0 #8F3D0E' }}>예약 취소</button>
            </div>
          </div>
        </div>
      )}
      <TabBar />
    </div>
  );
}
