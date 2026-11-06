import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader } from '../components/ui.jsx';
import { reservationApi } from '../api/index.js';

export default function RouteResult() {
  const navigate = useNavigate();
  const { state } = useLocation(); // 루트 추천 화면에서 넘겨준 결과
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  // 주소로 바로 들어와서 결과가 없으면 조건 화면으로 돌려보내요.
  if (!state?.result) return <Navigate to="/route" replace />;
  const { stops, totalRideMinutes, totalWalkMinutes, totalWaitMinutes, estimatedEndTime, excluded = [], ticketId } = state.result;

  // 추천된 곳을 차례대로 모두 예약 (서버가 잔여석·마감 여부를 다시 확인해요)
  async function reserveAll() {
    setError('');
    setSaving(true);
    const failed = [];
    let lastId = null;
    for (const s of stops) {
      try {
        const r = await reservationApi.create(s.slotId, ticketId);
        lastId = r.id;
      } catch (e) {
        failed.push(`${s.name}: ${e.message}`);
      }
    }
    setSaving(false);
    if (failed.length) setError(`일부는 예약하지 못했어요.\n${failed.join('\n')}`);
    else navigate('/my/reservations', { state: { doneId: lastId } });
  }

  return (
    <div className="screen">
      <BackHeader title="추천 루트" to="/route" />
      <div className="screen-body pad stack gap-14">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8, flexShrink: 0 }}>
          <Summary label="방문" value={`${stops.length}곳`} color="var(--pink-dark)" />
          <Summary label="총 탑승" value={`${totalRideMinutes}분`} />
          <Summary label="총 이동" value={`${totalWalkMinutes}분`} />
        </div>

        {stops.length === 0 ? (
          <div className="stack gap-4" style={{ border: '2px dashed #C9D4E3', borderRadius: 18, padding: '12px 14px', background: 'rgba(255,255,255,0.6)' }}>
            <span style={{ fontSize: 13, lineHeight: 1.5 }}>선택한 어트랙션이 모두 예약 불가해요. 다른 어트랙션을 선택해 주세요.</span>
            <Link to="/route" className="bold" style={{ fontSize: 13 }}>조건 다시 고르기</Link>
          </div>
        ) : (
          <ol style={{ listStyle: 'none', margin: 0, padding: 0 }} className="stack">
            {stops.map((s, i) => {
              const last = i === stops.length - 1;
              return (
                <li key={s.slotId} className="row gap-12" style={{ alignItems: 'stretch' }}>
                  <div className="stack" style={{ alignItems: 'center', width: 32, flexShrink: 0 }}>
                    <span className="display icon-circle" style={{ width: 32, height: 32, background: 'var(--pink)', color: '#fff', fontSize: 17 }}>{s.order}</span>
                    {!last && <span style={{ flex: 1, width: 0, borderLeft: '3px dotted #F3A8C4', margin: '4px 0' }} />}
                  </div>
                  <div className="stack grow gap-8" style={{ paddingBottom: last ? 0 : 8 }}>
                    <div className="card row gap-10" style={{ borderRadius: 18, padding: 8 }}>
                      <img src={s.image} alt={s.name} style={{ width: 52, height: 52, borderRadius: 12, objectFit: 'cover' }} />
                      <span className="stack grow" style={{ gap: 2 }}>
                        <span className="bold" style={{ fontSize: 15 }}>{s.name}</span>
                        <span className="small muted">탑승 {s.rideMinutes}분 · {s.time}~{s.endTime}{s.zone ? ` · ${s.zone}` : ''}</span>
                      </span>
                      <span className="display" style={{ fontSize: 18, color: 'var(--blue-ink)', paddingRight: 6 }}>{s.time}</span>
                    </div>
                    {!last && (
                      // 다음 장소까지의 이동·대기 시간은 "다음 방문" 정보에 들어 있어요.
                      <span className="row gap-4 small muted">
                        <Icon name="walk" size={14} strokeWidth={2} />
                        도보 이동 약 {stops[i + 1].moveMinutes}분
                        {stops[i + 1].waitMinutes > 0 && ` · 대기 약 ${stops[i + 1].waitMinutes}분`}
                      </span>
                    )}
                  </div>
                </li>
              );
            })}
          </ol>
        )}

        {stops.length > 0 && (
          <span className="small muted">총 대기 약 {totalWaitMinutes}분 · 예상 종료 {estimatedEndTime}</span>
        )}
        {excluded.length > 0 && (
          <div className="stack gap-4">
            <span className="small bold">추천에서 빠진 곳</span>
            {excluded.map((e) => (
              <span key={e.name} className="small muted">· {e.name}: {e.reason}</span>
            ))}
          </div>
        )}
        <div style={{ background: '#FFF8E1', borderRadius: 18, padding: '12px 14px', fontSize: 12, lineHeight: 1.55, color: '#5A3B00' }}>
          예약 시 잔여 정원과 예약 가능 여부를 다시 확인합니다. 그 사이 마감된 슬롯은 예약되지 않아요.
        </div>
        {error && <span className="error-text" role="alert" style={{ whiteSpace: 'pre-line' }}>{error}</span>}
      </div>

      <div className="bottom-bar" style={{ flexDirection: 'row' }}>
        <Link to="/route" className="btn btn-outline" style={{ flex: '0 0 116px' }}>다시 추천</Link>
        <button type="button" className="btn btn-primary" style={{ flex: 1 }} onClick={reserveAll} disabled={saving || stops.length === 0}>
          {saving ? '예약 중…' : `${stops.length}곳 모두 예약`}
        </button>
      </div>
    </div>
  );
}

function Summary({ label, value, color }) {
  return (
    <div className="card stack" style={{ alignItems: 'center', gap: 2, borderRadius: 18, padding: 10 }}>
      <span style={{ fontSize: 11, color: 'var(--muted)' }}>{label}</span>
      <span className="display" style={{ fontSize: 22, color: color || 'var(--ink)' }}>{value}</span>
    </div>
  );
}
