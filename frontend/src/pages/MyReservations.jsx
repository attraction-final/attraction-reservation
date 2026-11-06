import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackButton, Badge, Empty, ErrorBox, Loading, TabBar } from '../components/ui.jsx';
import ReservationCancelSheet from '../components/ReservationCancelSheet.jsx';
import { useApi } from '../hooks/useApi.js';
import { reservationApi } from '../api/index.js';
import { RESERVATION_STATUS, shortDate } from '../api/labels.js';

export default function MyReservations() {
  const { state } = useLocation(); // 예약 직후라면 { doneId: 'RSV-...' } 예약번호가 들어 있어요
  const [scope, setScope] = useState('UPCOMING'); // 'UPCOMING' 남은 예약 | 'ALL' 전체
  const { data, loading, error, reload } = useApi(() => reservationApi.mine(scope), [scope]);
  const upcomingCount = useApi(() => reservationApi.mine('UPCOMING'), [data]); // 탭 옆 숫자용

  const [target, setTarget] = useState(null); // 취소 확인창에 띄울 예약

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="card stack gap-12" style={{ borderRadius: '0 0 26px 26px', padding: '18px 16px 12px' }}>
          <div className="row between">
            <div className="title-with-back">
              <BackButton to="/" />
              <h1 className="h1">내 예약</h1>
            </div>
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
              {/* 위쪽 줄을 누르면 예약 상세 화면으로 가요 (취소·이용 완료 예약도 볼 수 있어요) */}
              <Link to={`/my/reservations/${r.id}`} className="row gap-12" aria-label={`${r.attractionName} 예약 상세`}
                style={{ padding: 12, textDecoration: 'none', color: 'inherit' }}>
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
              </Link>
              {r.status === 'RESERVED' && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', borderTop: '1px solid #EEF2F7' }}>
                  <Link to={`/attractions/${r.attractionId}`} className="row bold" style={{ height: 46, justifyContent: 'center', fontSize: 14, textDecoration: 'none', borderRight: '1px solid #EEF2F7' }}>어트랙션 보기</Link>
                  <button type="button" onClick={() => setTarget(r)} className="bold" style={{ height: 46, border: 0, background: 'transparent', fontSize: 14, color: 'var(--orange-ink)' }}>예약 취소</button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* 아래에서 올라오는 취소 확인창 (예약 상세 화면과 같은 컴포넌트) */}
      {target && (
        <ReservationCancelSheet
          reservation={target}
          onClose={() => setTarget(null)}
          onDone={() => { setTarget(null); reload(); }}
        />
      )}
      <TabBar />
    </div>
  );
}
