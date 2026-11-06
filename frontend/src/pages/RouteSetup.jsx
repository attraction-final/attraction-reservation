import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader, Empty, Loading } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { attractionApi, parkApi, routeApi, ticketApi } from '../api/index.js';
import { TICKET_TYPE, shortDate } from '../api/labels.js';
import { useAuth } from '../context/AuthContext.jsx';

// '10:00' ~ '22:00' 사이를 1시간 간격 목록으로 만들어요.
function hourOptions(open = '10:00', close = '22:00') {
  const list = [];
  for (let h = Number(open.slice(0, 2)); h <= Number(close.slice(0, 2)); h++) list.push(`${String(h).padStart(2, '0')}:00`);
  return list;
}

export default function RouteSetup() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const park = useApi(() => parkApi.today(), []);
  const tickets = useApi(() => (user ? ticketApi.mine('USABLE') : Promise.resolve([])), [user]);
  const rides = useApi(() => attractionApi.list('ALL'), []);

  const [start, setStart] = useState('14:00');
  const [end, setEnd] = useState('18:00');
  const [picked, setPicked] = useState([]); // 고른 어트랙션 id 목록
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  if (!user) {
    return (
      <div className="screen">
        <BackHeader title="루트 추천" to="/" />
        <Empty>루트 추천은 로그인 후 이용할 수 있어요.<br /><Link to="/login">로그인하러 가기</Link></Empty>
      </div>
    );
  }
  if (rides.loading || tickets.loading) return <div className="screen"><BackHeader title="루트 추천" to="/" /><Loading /></div>;

  const ticket = tickets.data?.[0];
  const hours = hourOptions(park.data?.openTime, park.data?.closeTime);
  const MAX_PICK = 5; // 백엔드 규칙: 어트랙션은 1~5개 (CreateRouteRecommendationRequest @Size)
  const toggle = (id) => {
    if (picked.includes(id)) return setPicked(picked.filter((x) => x !== id));
    if (picked.length >= MAX_PICK) return setError(`어트랙션은 최대 ${MAX_PICK}개까지 고를 수 있어요.`);
    setError('');
    setPicked([...picked, id]);
  };

  async function submit() {
    setError('');
    if (start >= end) return setError('종료 시간은 시작 시간보다 늦어야 해요.');
    if (picked.length === 0) return setError('어트랙션을 1개 이상 골라 주세요.');
    setSubmitting(true);
    try {
      const result = await routeApi.recommend({ ticketId: ticket?.id, date: ticket?.date, startTime: start, endTime: end, attractionIds: picked });
      // 결과 화면으로 이동하면서 추천 결과를 같이 넘겨요.
      navigate('/route/result', { state: { result } });
    } catch (e) {
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
          <span className="icon-circle" style={{ width: 44, height: 44, borderRadius: 14, background: '#fff', color: 'var(--pink-dark)' }}><Icon name="ticket" /></span>
          <span className="stack grow" style={{ gap: 2 }}>
            <span className="small" style={{ color: '#8C2049' }}>적용 입장권</span>
            <span className="bold" style={{ fontSize: 15 }}>{ticket ? `${TICKET_TYPE[ticket.type]} · ${shortDate(ticket.date)}` : '사용 가능한 입장권 없음'}</span>
          </span>
          <span className="small" style={{ color: '#8C2049', textAlign: 'right' }}>이용 가능<br />{park.data ? `${park.data.openTime}–${park.data.closeTime}` : ''}</span>
        </div>

        <div className="card stack gap-10" style={{ borderRadius: 22, padding: 16, flexShrink: 0 }}>
          <h2 className="display" style={{ fontSize: 18 }}>이용 가능한 시간</h2>
          <div className="row gap-8" style={{ alignItems: 'flex-end' }}>
            <TimeSelect id="start" label="시작" value={start} onChange={setStart} options={hours} />
            <span className="muted" style={{ paddingBottom: 14 }}>~</span>
            <TimeSelect id="end" label="종료" value={end} onChange={setEnd} options={hours} />
          </div>
          <span className="small muted">오후권은 15:00 이후만 선택할 수 있어요. 이미 지난 시작 시간은 지금 시각부터 계산돼요.</span>
        </div>

        <div className="stack gap-8">
          <div className="row between" style={{ alignItems: 'baseline' }}>
            <h2 className="display" style={{ fontSize: 18 }}>선호 어트랙션</h2>
            <span className="small bold" style={{ color: 'var(--blue-ink)' }}>{picked.length}/5개 선택됨</span>
          </div>
          {(rides.data || []).map((a) => {
            const closed = a.status !== 'OPERATING' || a.soldOut;
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
                <span className="small muted">{closed ? (a.soldOut ? '예약 마감' : '운영 안 함') : `${a.rideMinutes}분`}</span>
              </button>
            );
          })}
        </div>
        {error && <span className="error-text" role="alert">{error}</span>}
      </div>

      <div className="bottom-bar">
        <button type="button" className="btn btn-pink" onClick={submit} disabled={submitting}>
          <Icon name="map" size={20} strokeWidth={2} />
          {submitting ? '추천 중…' : '루트 추천받기'}
        </button>
      </div>
    </div>
  );
}

function TimeSelect({ id, label, value, onChange, options }) {
  return (
    <div className="stack grow gap-4">
      <label htmlFor={id} className="small muted">{label}</label>
      <select id={id} className="input" value={value} onChange={(e) => onChange(e.target.value)} style={{ padding: '0 12px' }}>
        {options.map((o) => <option key={o}>{o}</option>)}
      </select>
    </div>
  );
}
