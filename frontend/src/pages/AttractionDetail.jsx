import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Badge, ErrorBox, FloatingBack, Loading } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { attractionApi, parkApi, reservationApi, ticketApi } from '../api/index.js';
import { TICKET_STATUS, TICKET_TYPE, dayOfWeek, shortDate } from '../api/labels.js';
import { useAuth } from '../context/AuthContext.jsx';
import { statusOf } from './Attractions.jsx';

// 슬롯을 고를 수 없는 이유 → 화면 문구
const REASON_TEXT = { PAST: '예약 시간 지남', FULL: '마감', CLOSED: '운영 안 함', MINE: '내 예약', CONFLICT: '다른 예약과 겹침' };

export default function AttractionDetail() {
  const { id } = useParams(); // 주소 /attractions/3 의 3
  const navigate = useNavigate();
  const { user } = useAuth();

  const park = useApi(() => parkApi.today(), []);
  const ride = useApi(() => attractionApi.detail(id), [id]);
  const slots = useApi(() => (park.data ? attractionApi.slots(id, park.data.date) : Promise.resolve([])), [id, park.data]);
  const tickets = useApi(() => (user ? ticketApi.mine('USABLE') : Promise.resolve([])), [user]);

  const [slotId, setSlotId] = useState(null);
  const [ticketId, setTicketId] = useState('');
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 슬롯 목록이 오면 "예약 가능한 첫 시간"을 기본으로 골라 둬요.
  useEffect(() => {
    const first = slots.data?.find((s) => s.bookable);
    setSlotId(first ? first.id : null);
  }, [slots.data]);

  // 입장권 목록이 오면 첫 번째를 기본 선택
  useEffect(() => {
    if (tickets.data?.length) setTicketId(tickets.data[0].id);
  }, [tickets.data]);

  if (ride.loading) return <div className="screen"><Loading /></div>;
  if (ride.error) return <div className="screen"><ErrorBox error={ride.error} onRetry={ride.reload} /></div>;

  const a = ride.data;
  const picked = slots.data?.find((s) => s.id === slotId);

  async function reserve() {
    if (!user) return navigate('/login');
    setError('');
    setSubmitting(true);
    try {
      const r = await reservationApi.create(slotId, ticketId);
      navigate('/my/reservations', { state: { doneId: r.id } }); // 완료 안내를 띄우려고 예약번호를 같이 넘겨요
    } catch (e) {
      setError(e.message);
      slots.reload(); // 그 사이 마감됐을 수 있으니 최신 상태로
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="screen">
      <div className="hero" style={{ height: 240 }}>
        <img src={a.image} alt={a.name} />
        <FloatingBack to="/attractions" />
      </div>

      <div className="stack gap-14" style={{ flex: 1, minHeight: 0, overflowY: 'auto', marginTop: -26, position: 'relative', background: '#fff', borderRadius: '28px 28px 0 0', padding: '22px 18px 16px' }}>
        <div className="stack gap-4">
          <div className="row gap-8">
            <h1 className="display" style={{ fontSize: 28 }}>{a.name}</h1>
            <Badge status={statusOf(a)} />
          </div>
          <p className="muted" style={{ margin: 0, fontSize: 13, lineHeight: 1.5 }}>{a.description || '[어트랙션 설명 1–2줄]'}</p>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }}>
          <InfoTile label="탑승 시간" value={`${a.rideMinutes}분`} />
          <InfoTile label="위치" value={a.zone} small />
          <InfoTile label="키 제한" value={heightText(a)} small dashed={!a.heightLimited} />
        </div>
        {a.guardianHeightCm != null && (
          <span className="small muted" style={{ marginTop: -6 }}>
            키 {a.guardianHeightCm}cm 이하는 보호자와 함께 타야 해요.
          </span>
        )}

        <div className="stack gap-10">
          <div className="row between" style={{ alignItems: 'baseline' }}>
            <h2 className="h2">예약 시간 선택</h2>
            {park.data && <span className="small muted">{shortDate(park.data.date)} ({dayOfWeek(park.data.date)}) · 현재 {park.data.now}</span>}
          </div>
          {slots.data?.length === 0 && <span className="small muted">오늘 예약할 수 있는 시간이 없어요.</span>}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }}>
            {(slots.data || []).map((s) => (
              <SlotButton key={s.id} slot={s} on={s.id === slotId} onPick={() => setSlotId(s.id)} />
            ))}
          </div>
        </div>

        <div className="field">
          <label htmlFor="ticket" className="display" style={{ fontSize: 17, fontWeight: 400 }}>사용할 입장권</label>
          {user ? (
            <select id="ticket" className="input" value={ticketId} onChange={(e) => setTicketId(e.target.value)} style={{ fontSize: 14 }}>
              {(tickets.data || []).length === 0 && <option value="">사용 가능한 입장권이 없어요</option>}
              {(tickets.data || []).map((t) => (
                <option key={t.id} value={t.id}>{TICKET_TYPE[t.type]} · {t.id} · {TICKET_STATUS[t.status].label}</option>
              ))}
            </select>
          ) : (
            <span className="small muted">로그인하면 내 입장권을 고를 수 있어요.</span>
          )}
        </div>
        {error && <span className="error-text" role="alert">{error}</span>}
      </div>

      <div className="stack gap-8" style={{ flexShrink: 0, background: '#fff', borderTop: '1px solid #E3ECF6', padding: '10px 18px 16px' }}>
        <span className="small muted">이용 30분 전까지 예약할 수 있어요.</span>
        <button type="button" className="btn btn-primary" onClick={reserve}
          disabled={submitting || !picked || (user && !ticketId)}>
          {!user ? '로그인하고 예약하기' : picked ? `${picked.startTime} 예약하기` : '예약할 시간을 골라 주세요'}
        </button>
      </div>
    </div>
  );
}

// small: 글자가 긴 값(위치, 키 제한)은 조금 작게 보여 준다
function InfoTile({ label, value, dashed, small }) {
  return (
    <div className="stack" style={{ alignItems: 'center', gap: 2, borderRadius: 16, padding: '10px 6px', background: dashed ? '#F7F9FC' : 'var(--soft)', border: dashed ? '1.5px dashed #C9D4E3' : 0 }}>
      <span style={{ fontSize: 11, color: 'var(--muted)' }}>{label}</span>
      <span
        className="display"
        style={{ fontSize: small ? 15 : 18, textAlign: 'center', lineHeight: 1.3, wordBreak: 'keep-all' }}
      >
        {value}
      </span>
    </div>
  );
}

// 키 제한 문구 만들기
// 최소+최대: "130~195cm" / 최소만: "110cm 이상" / 최대만: "195cm 이하" / 둘 다 없음: "정보 없음"
function heightText(a) {
  const min = a.minHeightCm;
  const max = a.maxHeightCm;
  if (min != null && max != null) return `${min}~${max}cm`;
  if (min != null) return `${min}cm 이상`;
  if (max != null) return `${max}cm 이하`;
  return '정보 없음';
}

// 시간 버튼 하나: 상태에 따라 색이 달라져요.
function SlotButton({ slot, on, onPick }) {
  const disabled = !slot.bookable;
  const clash = slot.reason === 'CONFLICT' || slot.reason === 'MINE';
  let box = { background: '#fff', border: '1.5px solid var(--line)' };
  let time = { color: 'var(--ink)' };
  let sub = { color: 'var(--blue-ink)' };
  if (on) {
    box = { background: 'var(--blue)', border: '1.5px solid var(--blue)', boxShadow: '0 3px 0 var(--blue-dark)' };
    time = { color: '#fff' };
    sub = { color: '#fff', fontWeight: 700 };
  } else if (clash) {
    box = { background: '#FFF6EC', border: '1.5px solid #F4C99A' };
    sub = { color: 'var(--orange-ink)', fontWeight: 700 };
  } else if (disabled) {
    box = { background: '#F1F3F7', border: '1.5px solid #F1F3F7' };
    time = { color: '#8A94AB', textDecoration: slot.reason === 'PAST' ? 'line-through' : 'none' };
    sub = { color: 'var(--subtle)' };
  }
  return (
    <button type="button" disabled={disabled} aria-pressed={on} onClick={onPick}
      style={{ height: 62, borderRadius: 16, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1, ...box }}>
      <span className="display" style={{ fontSize: 18, ...time }}>{slot.startTime}</span>
      <span style={{ fontSize: 11, ...sub }}>{slot.reason ? REASON_TEXT[slot.reason] : `잔여 ${slot.remaining}/${slot.capacity}`}</span>
    </button>
  );
}
