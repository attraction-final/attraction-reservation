import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader, Empty, Loading } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { ROUTE_PICK_COUNT, routeApi } from '../api/index.js';
import { getCurrentLocation } from '../api/geolocation.js';
import { useAuth } from '../context/AuthContext.jsx';

// 어트랙션 5개를 고르면 지금 위치에서 출발해 걷는 시간이 가장 짧은 방문 순서를 추천받아요.
export default function RouteSetup() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const rides = useApi(() => (user ? routeApi.recommendableAttractions() : Promise.resolve([])), [user]);

  const [picked, setPicked] = useState([]); // 고른 어트랙션 id 목록
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [locating, setLocating] = useState(false); // 현재 위치를 받는 중
  const errorRef = useRef(null);

  useEffect(() => {
    if (error) errorRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [error]);

  if (!user) {
    return (
      <div className="screen">
        <BackHeader title="루트 추천" to="/" />
        <Empty>루트 추천은 로그인 후 이용할 수 있어요.<br /><Link to="/login">로그인하러 가기</Link></Empty>
      </div>
    );
  }
  if (rides.loading) return <div className="screen"><BackHeader title="루트 추천" to="/" /><Loading /></div>;

  const toggle = (id) => {
    if (picked.includes(id)) return setPicked(picked.filter((x) => x !== id));
    if (picked.length >= ROUTE_PICK_COUNT) return setError(`어트랙션은 ${ROUTE_PICK_COUNT}개까지 고를 수 있어요.`);
    setError('');
    setPicked([...picked, id]);
  };

  async function submit() {
    setError('');
    if (picked.length !== ROUTE_PICK_COUNT) return setError(`어트랙션을 정확히 ${ROUTE_PICK_COUNT}개 골라 주세요.`);
    setSubmitting(true);

    // 버튼을 누른 이때 위치를 받아요. (화면에 들어오자마자 권한 팝업이 뜨지 않게)
    // 위치를 못 받으면 요청을 보내지 않아요. 서버는 위치 없이 추천하지 않아요.
    let position;
    setLocating(true);
    try {
      position = await getCurrentLocation();
    } catch (e) {
      setError(e.message);
      setSubmitting(false);
      return;
    } finally {
      setLocating(false);
    }

    try {
      const result = await routeApi.recommend({ position, attractionIds: picked });
      // 결과 화면으로 이동하면서 추천 결과를 같이 넘겨요.
      navigate('/route/result', { state: { result } });
    } catch (e) {
      // 위치 정확도가 낮음·공원에서 너무 멂·이동 경로 없음 등은 서버 메시지를 그대로 보여 줘요.
      setError(e.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="screen">
      <BackHeader title="루트 추천" to="/" />
      <div className="screen-body pad stack gap-16">
        <div className="row gap-12" style={{ background: 'var(--pink-soft)', borderRadius: 22, padding: '14px 16px', flexShrink: 0 }}>
          <span className="icon-circle" style={{ width: 44, height: 44, borderRadius: 14, background: '#fff', color: 'var(--pink-dark)' }}><Icon name="map" /></span>
          <span className="stack grow" style={{ gap: 2 }}>
            <span className="bold" style={{ fontSize: 15 }}>지금 위치에서 출발해요</span>
            <span className="small" style={{ color: '#8C2049' }}>
              추천받기를 누르면 위치 권한을 물어봐요. 위치는 이번 추천에만 쓰고 저장하지 않아요.
            </span>
          </span>
        </div>

        <div className="stack gap-8">
          <div className="row between" style={{ alignItems: 'baseline' }}>
            <h2 className="display" style={{ fontSize: 18 }}>방문할 어트랙션</h2>
            <span className="small bold" style={{ color: 'var(--blue-ink)' }}>{picked.length}/{ROUTE_PICK_COUNT}개 선택됨</span>
          </div>
          <span className="small muted">
            {ROUTE_PICK_COUNT}개를 고르면 걷는 시간이 가장 짧은 순서를 알려 드려요. 이동 정보가 준비된 어트랙션만 보여요.
          </span>
          {rides.error && <span className="error-text" role="alert">{rides.error.message}</span>}
          {!rides.error && (rides.data || []).length < ROUTE_PICK_COUNT && (
            <span className="small muted">지금은 추천할 수 있는 어트랙션이 {ROUTE_PICK_COUNT}개보다 적어요. 잠시 후 다시 확인해 주세요.</span>
          )}
          {(rides.data || []).map((a) => {
            const closed = a.status !== 'OPERATING';
            const on = picked.includes(a.id);
            return (
              <button key={a.id} type="button" role="checkbox" aria-checked={on} disabled={closed} onClick={() => toggle(a.id)} className="row gap-12"
                style={{
                  width: '100%', height: 60, padding: '0 14px 0 12px', borderRadius: 18, flexShrink: 0,
                  ...(closed ? { background: '#F1F3F7', border: '1.5px dashed #C9D4E3', opacity: 0.75 }
                    : on ? { background: '#F2F7FF', border: '2px solid var(--blue)' }
                    : { background: '#fff', border: '2px solid #fff', boxShadow: 'var(--shadow)' }),
                }}>
                <span className="icon-circle" style={{ width: 24, height: 24, borderRadius: 8, background: on ? 'var(--blue)' : '#fff', border: `2px solid ${on ? 'var(--blue)' : '#AEB8CB'}`, color: '#fff' }}>
                  {on && <Icon name="check" size={15} strokeWidth={3} />}
                </span>
                <img src={a.image} alt="" style={{ width: 40, height: 40, borderRadius: 12, objectFit: 'cover' }} />
                <span className="grow bold" style={{ textAlign: 'left', fontSize: 15 }}>{a.name}</span>
                <span className="small muted">{closed ? '운영 안 함' : a.zone}</span>
              </button>
            );
          })}
        </div>
        {error && (
          // 목록 아래에 있어서 실패하면 이 영역이 보이도록 스크롤해요.
          <span ref={errorRef} className="error-text" role="alert" style={{ flexShrink: 0 }}>{error}</span>
        )}
      </div>

      <div className="bottom-bar">
        <button type="button" className="btn btn-pink" onClick={submit} disabled={submitting || picked.length !== ROUTE_PICK_COUNT}>
          <Icon name="map" size={20} strokeWidth={2} />
          {locating ? '현재 위치 확인 중…' : submitting ? '추천 중…' : '루트 추천받기'}
        </button>
      </div>
    </div>
  );
}
