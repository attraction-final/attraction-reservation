import { Link, Navigate, useLocation } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader } from '../components/ui.jsx';

// 1234 → "1.2km", 850 → "850m"
const formatDistance = (m) => (m >= 1000 ? `${(m / 1000).toFixed(1)}km` : `${m}m`);

export default function RouteResult() {
  const { state } = useLocation(); // 루트 추천 화면에서 넘겨준 결과

  // 주소로 바로 들어와서 결과가 없으면 조건 화면으로 돌려보내요.
  if (!state?.result) return <Navigate to="/route" replace />;
  const { visits, totalMoveMinutes, totalDistanceM, accuracyM, stalePosition, unavailablePaths = [] } = state.result;

  return (
    <div className="screen">
      <BackHeader title="추천 루트" to="/route" />
      <div className="screen-body pad stack gap-14">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8, flexShrink: 0 }}>
          <Summary label="방문" value={`${visits.length}곳`} color="var(--pink-dark)" />
          <Summary label="총 이동" value={`${totalMoveMinutes}분`} />
          <Summary label="총 거리" value={formatDistance(totalDistanceM)} />
        </div>
        <span className="row gap-4 small" style={{ color: 'var(--blue-ink)', flexShrink: 0 }}>
          <Icon name="map" size={14} strokeWidth={2} />
          <span className="bold">현재 위치 출발</span>
          <span className="muted">· 위치 오차 약 {accuracyM}m</span>
        </span>
        {stalePosition && (
          <div style={{ background: '#FFF8E1', borderRadius: 14, padding: '10px 12px', fontSize: 12, lineHeight: 1.5, color: '#5A3B00', flexShrink: 0 }}>
            오래전에 확인한 위치로 계산했어요. 지금 위치와 다르면 다시 추천받아 주세요.
          </div>
        )}

        <ol style={{ listStyle: 'none', margin: 0, padding: 0 }} className="stack">
          {visits.map((v, i) => {
            const last = i === visits.length - 1;
            return (
              <li key={v.attractionId} className="stack" style={{ gap: 8, paddingBottom: last ? 0 : 8 }}>
                {/* 이전 장소(처음엔 현재 위치)에서 이 방문지까지의 이동 */}
                <span className="row gap-4 small muted" style={{ paddingLeft: 44 }}>
                  <Icon name="walk" size={14} strokeWidth={2} />
                  {v.fromUserLocation ? '현재 위치에서 ' : ''}도보 약 {v.moveMinutes}분 · {formatDistance(v.distanceM)}
                  {v.estimated && ' (추정)'}
                </span>
                <div className="row gap-12" style={{ alignItems: 'center' }}>
                  <span className="display icon-circle" style={{ width: 32, height: 32, flexShrink: 0, background: 'var(--pink)', color: '#fff', fontSize: 17 }}>{v.order}</span>
                  <Link to={`/attractions/${v.attractionId}`} className="card row gap-10 grow" style={{ borderRadius: 18, padding: 8, color: 'inherit', textDecoration: 'none' }}>
                    <img src={v.image} alt={v.name} style={{ width: 52, height: 52, borderRadius: 12, objectFit: 'cover' }} />
                    <span className="stack grow" style={{ gap: 2 }}>
                      <span className="bold" style={{ fontSize: 15 }}>{v.name}</span>
                      {v.zone && <span className="small muted">{v.zone}</span>}
                    </span>
                    <Icon name="chevron" size={18} strokeWidth={2} />
                  </Link>
                </div>
              </li>
            );
          })}
        </ol>

        <span className="small muted">
          첫 이동은 지금 위치와 어트랙션 좌표로 추정한 시간이에요. "(추정)"이 없는 구간은 실제로 걸어서 잰 시간이에요.
        </span>
        {unavailablePaths.length > 0 && (
          <div className="stack gap-4">
            <span className="small bold">계산에서 뺀 이동 구간</span>
            {unavailablePaths.map((p) => (
              <span key={`${p.from}-${p.to}`} className="small muted">· {p.from} → {p.to}: {p.reason}</span>
            ))}
          </div>
        )}
        <div style={{ background: '#FFF8E1', borderRadius: 18, padding: '12px 14px', fontSize: 12, lineHeight: 1.55, color: '#5A3B00' }}>
          이동 순서만 추천해요. 탑승 예약은 각 어트랙션 화면에서 해 주세요.
        </div>
      </div>

      <div className="bottom-bar">
        <Link to="/route" className="btn btn-outline">다시 추천</Link>
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
