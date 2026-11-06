import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi, parkApi } from '../../api/index.js';
import { ATTRACTION_STATUS } from '../../api/labels.js';

export default function AdminSlots() {
  const navigate = useNavigate();
  const { state } = useLocation(); // 등록·삭제 후 돌아오면 { message } 가 들어 있어요
  const park = useApi(() => parkApi.today(), []);
  const rides = useApi(() => adminApi.attractions(), []);
  const [rideId, setRideId] = useState(null);
  const [date, setDate] = useState('');
  const slots = useApi(() => (rideId && date ? adminApi.slots(rideId, date) : Promise.resolve([])), [rideId, date]);

  const [form, setForm] = useState({ startTime: '', capacity: 20 });
  const [editing, setEditing] = useState(null); // 수정 중인 슬롯 { id, startTime, capacity } (없으면 null)
  const [message, setMessage] = useState(state?.message || '');

  useEffect(() => { if (rides.data?.length && !rideId) setRideId(rides.data[0].id); }, [rides.data, rideId]);
  useEffect(() => { if (park.data && !date) setDate(park.data.date); }, [park.data, date]);

  // 서버 요청을 하고, 성공하면 목록을 새로 불러오는 공통 함수
  async function run(action, okText) {
    setMessage('');
    try {
      await action();
      if (okText) setMessage(okText);
      slots.reload();
    } catch (e) {
      setMessage(e.message);
    }
  }

  const ride = rides.data?.find((r) => r.id === rideId);

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="어트랙션 · 슬롯"
          right={<button type="button" className="btn btn-primary btn-sm" style={{ height: 40, fontSize: 13, boxShadow: '0 3px 0 var(--blue-dark)' }} onClick={() => navigate('/admin/attractions/new')}>+ 어트랙션 등록</button>} />

        {rides.loading && <Loading />}
        {rides.error && <ErrorBox error={rides.error} onRetry={rides.reload} />}

        {/* 어트랙션 고르기 (옆으로 넘기기) */}
        <div className="row gap-10" style={{ padding: '14px 16px 4px', overflowX: 'auto', alignItems: 'stretch' }}>
          {(rides.data || []).map((r) => {
            const on = r.id === rideId;
            return (
              <button key={r.id} type="button" aria-pressed={on} onClick={() => setRideId(r.id)} className="stack"
                style={{ width: 108, flexShrink: 0, padding: '6px 6px 8px', borderRadius: 18, gap: 5, textAlign: 'left', ...(on ? { background: '#F2F7FF', border: '2px solid var(--blue)' } : { background: '#fff', border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                <img src={r.image} alt="" style={{ width: '100%', height: 58, borderRadius: 12, objectFit: 'cover' }} />
                <span className="bold" style={{ fontSize: 13, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.name}</span>
                <Badge status={ATTRACTION_STATUS[r.status]} small style={{ alignSelf: 'flex-start' }} />
              </button>
            );
          })}
        </div>

        <div className="pad stack gap-10" style={{ paddingTop: 10 }}>
          {/* 고른 어트랙션 정보 + 수정 화면으로 가기 */}
          {ride && (
            <div className="card row between gap-10" style={{ padding: 12 }}>
              <span className="stack" style={{ gap: 2, minWidth: 0 }}>
                <span className="bold" style={{ fontSize: 15 }}>{ride.name}</span>
                <span className="small muted">{ride.zone} · 탑승 {ride.rideMinutes}분 · {ATTRACTION_STATUS[ride.status]?.label}</span>
              </span>
              <button type="button" className="btn btn-sm btn-outline" style={{ height: 38, padding: '0 12px', fontSize: 13, flexShrink: 0 }}
                onClick={() => navigate(`/admin/attractions/${ride.id}/edit`)}>
                정보 수정
              </button>
            </div>
          )}
          <div className="row between">
            <h2 className="h2">{ride ? `${ride.name} 슬롯` : '슬롯'}</h2>
            <input type="date" aria-label="운영일" value={date} onChange={(e) => setDate(e.target.value)}
              style={{ height: 38, border: '1.5px solid var(--line)', borderRadius: 12, padding: '0 8px', fontSize: 13, background: '#fff' }} />
          </div>

          {/* 슬롯 추가 */}
          <div className="card stack gap-8" style={{ padding: 12 }}>
            <div className="row gap-8" style={{ alignItems: 'flex-end' }}>
              <span className="stack grow gap-4">
                <label htmlFor="st" style={{ fontSize: 11, color: 'var(--muted)' }}>시작 시간</label>
                <input id="st" type="time" className="input" value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} style={{ height: 42, borderRadius: 12, padding: '0 8px', fontSize: 14 }} />
              </span>
              <span className="stack gap-4" style={{ width: 72 }}>
                <label htmlFor="cap" style={{ fontSize: 11, color: 'var(--muted)' }}>정원</label>
                <input id="cap" type="number" min={1} className="input" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} style={{ height: 42, borderRadius: 12, padding: '0 8px', fontSize: 14 }} />
              </span>
              <button type="button" className="btn btn-sm btn-dark" style={{ padding: '0 12px', fontSize: 13 }}
                onClick={() => run(() => adminApi.createSlot(rideId, { date, startTime: form.startTime, capacity: Number(form.capacity) }), '슬롯을 추가했어요.')}>
                + 추가
              </button>
            </div>
            <button type="button" className="bold" style={{ height: 44, borderRadius: 12, border: '1.5px dashed #AEB8CB', background: 'var(--field)', fontSize: 13 }}
              onClick={() => run(async () => { const r = await adminApi.generateSlots(rideId, date); setMessage(`${r.created}개 슬롯을 만들었어요. (이미 있거나 만들 수 없는 시간 ${r.skipped}개는 건너뛰었어요)`); })}>
              운영 시간으로 슬롯 일괄 생성
            </button>
          </div>
          {message && <span className="small" style={{ color: 'var(--blue-ink)' }}>{message}</span>}

          {slots.loading && <Loading />}
          {slots.data?.length === 0 && !slots.loading && <span className="small muted">이 날짜에는 슬롯이 없어요.</span>}
          {slots.data?.map((s) => {
            // 연필을 누른 슬롯은 수정용 줄로 바꿔서 보여 줘요.
            if (editing?.id === s.id) {
              return (
                <SlotEditRow key={s.id} slot={s} value={editing} onChange={setEditing}
                  onCancel={() => setEditing(null)}
                  onSave={async () => {
                    setMessage('');
                    const result = await saveSlotEdit(s, editing, date);
                    setMessage(result.message);
                    if (result.done) setEditing(null);
                    slots.reload();
                  }} />
              );
            }
            const full = s.remaining <= 0;
            const closed = s.status === 'CLOSED' || s.status === 'SUSPENDED'; // 예약 마감 또는 운영 중단
            const pct = Math.round((s.reservedCount / s.capacity) * 100);
            const barColor = closed || full ? '#8A94AB' : s.remaining <= 3 ? 'var(--pink)' : 'var(--blue)';
            return (
              <div key={s.id} className="row gap-10" style={{ padding: '10px 10px 10px 14px', borderRadius: 18, ...(closed || full ? { background: 'rgba(255,255,255,0.6)', border: '1.5px solid #DCE5F0' } : { background: '#fff', boxShadow: 'var(--shadow)' }) }}>
                <span className="stack" style={{ width: 52, flexShrink: 0 }}>
                  <span className="display" style={{ fontSize: 19, color: closed ? 'var(--muted)' : 'var(--ink)' }}>{s.startTime}</span>
                  <span className="small muted" style={{ fontSize: 11 }}>~{s.endTime}</span>
                </span>
                <span className="stack grow" style={{ gap: 5 }}>
                  {/* 좁은 화면에서 잘리지 않게 두 줄로 나눠요: 1줄 = 예약/정원, 2줄 = 잔여 */}
                  <span className="stack" style={{ gap: 1 }}>
                    <span className="small muted" style={{ whiteSpace: 'nowrap' }}>예약 {s.reservedCount} / 정원 {s.capacity}</span>
                    <b className="small" style={{ color: full ? 'var(--muted)' : s.remaining <= 3 ? 'var(--pink-dark)' : 'var(--blue-ink)' }}>잔여 {s.remaining}</b>
                  </span>
                  <span style={{ height: 8, borderRadius: 4, background: 'var(--blue-soft)', overflow: 'hidden', display: 'block' }}>
                    <span style={{ display: 'block', height: '100%', width: `${pct}%`, background: barColor }} />
                  </span>
                </span>
                <select aria-label={`${s.startTime} 슬롯 상태`} value={s.status}
                  onChange={(e) => run(() => adminApi.updateSlot(s.id, e.target.value))}
                  style={{ height: 36, borderRadius: 10, padding: '0 6px', fontSize: 12, ...(closed ? { border: '1.5px solid #F3A8C4', background: '#FFF7FA', color: 'var(--pink-dark)' } : { border: '1.5px solid var(--line)', background: '#fff' }) }}>
                  <option value="OPEN">운영</option>
                  <option value="CLOSED">마감</option>
                  <option value="SUSPENDED">운영 중단</option>
                </select>
                <button type="button" aria-label={`${s.startTime} 슬롯 수정`}
                  onClick={() => { setMessage(''); setEditing({ id: s.id, startTime: s.startTime, capacity: String(s.capacity) }); }}
                  className="icon-circle" style={{ width: 36, height: 36, borderRadius: 10, border: 0, background: 'var(--blue-soft)', color: 'var(--blue-ink)' }}>
                  <Icon name="edit" size={17} strokeWidth={2} />
                </button>
                <button type="button" aria-label={`${s.startTime} 슬롯 삭제`}
                  onClick={() => window.confirm(`${s.startTime} 슬롯을 삭제할까요?`) && run(() => adminApi.deleteSlot(s.id), '슬롯을 삭제했어요.')}
                  className="icon-circle" style={{ width: 36, height: 36, borderRadius: 10, border: 0, background: '#FFF1E6', color: 'var(--orange-ink)' }}>
                  <Icon name="trash" size={17} strokeWidth={2} />
                </button>
              </div>
            );
          })}
          <p className="small muted" style={{ margin: 0 }}>예약이 있는 슬롯은 삭제하거나 운영 중단할 수 없어요. 종료 시각은 어트랙션 탑승 시간으로 자동 계산돼요.</p>
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}

// 수정 내용을 저장해요. 시간·정원 API가 따로라서 "바뀐 것만" 차례로 보내요.
// 둘 다 바꿨는데 하나만 성공하면, 어디까지 됐는지 알려 줘요. (API가 나뉘어 있어 한 번에 되돌릴 수 없어요)
async function saveSlotEdit(slot, edit, date) {
  const timeChanged = edit.startTime !== slot.startTime;
  const capacityChanged = Number(edit.capacity) !== slot.capacity;
  if (!timeChanged && !capacityChanged) return { done: true, message: '바뀐 내용이 없어요.' };

  // 저장 전에 백엔드와 같은 규칙으로 먼저 확인해요.
  if (!edit.startTime) return { done: false, message: '시작 시간을 입력해 주세요.' };
  if (!(Number(edit.capacity) >= 1)) return { done: false, message: '정원은 1명 이상이어야 해요.' };
  if (Number(edit.capacity) < slot.reservedCount) {
    return { done: false, message: `이미 ${slot.reservedCount}명이 예약해서 정원을 그보다 적게 줄일 수 없어요.` };
  }

  let timeDone = false;
  try {
    if (timeChanged) {
      await adminApi.updateSlotTime(slot.id, date, edit.startTime);
      timeDone = true;
    }
    if (capacityChanged) await adminApi.updateSlotCapacity(slot.id, edit.capacity);
    return { done: true, message: '슬롯을 수정했어요.' };
  } catch (e) {
    if (timeDone) return { done: true, message: `시간은 바꿨지만 정원은 바꾸지 못했어요: ${e.message}` };
    return { done: false, message: e.message };
  }
}

// 수정 중인 슬롯 한 줄 (그 자리에서 펼쳐지는 입력 칸)
function SlotEditRow({ slot, value, onChange, onCancel, onSave }) {
  const [saving, setSaving] = useState(false);
  const timeLocked = slot.reservedCount > 0; // 백엔드 규칙: 예약이 있으면 시간을 바꿀 수 없어요 (BR-SLT-06)
  const minCapacity = Math.max(1, slot.reservedCount); // 정원은 예약 수보다 적게 못 줄여요 (BR-SLT-05)

  async function save() {
    setSaving(true);
    try { await onSave(); } finally { setSaving(false); }
  }

  return (
    <div className="stack gap-8" style={{ padding: 12, borderRadius: 18, background: '#fff', border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' }}>
      <div className="row gap-8" style={{ alignItems: 'flex-end' }}>
        <span className="stack grow gap-4">
          <label htmlFor={`edit-time-${slot.id}`} style={{ fontSize: 11, color: 'var(--muted)' }}>시작 시간</label>
          <input id={`edit-time-${slot.id}`} type="time" className="input" value={value.startTime} disabled={timeLocked}
            onChange={(e) => onChange({ ...value, startTime: e.target.value })}
            style={{ height: 42, borderRadius: 12, padding: '0 8px', fontSize: 14, ...(timeLocked ? { background: 'var(--gray-soft)', color: 'var(--subtle)' } : {}) }} />
        </span>
        <span className="stack gap-4" style={{ width: 92 }}>
          <label htmlFor={`edit-cap-${slot.id}`} style={{ fontSize: 11, color: 'var(--muted)' }}>정원 ({minCapacity} 이상)</label>
          <input id={`edit-cap-${slot.id}`} type="number" min={minCapacity} className="input" value={value.capacity}
            onChange={(e) => onChange({ ...value, capacity: e.target.value })}
            style={{ height: 42, borderRadius: 12, padding: '0 8px', fontSize: 14 }} />
        </span>
      </div>
      {timeLocked && <span className="small muted">예약이 있어 시간은 바꿀 수 없어요. 정원만 바꿀 수 있어요.</span>}
      <div className="row gap-8" style={{ justifyContent: 'flex-end' }}>
        <button type="button" className="btn btn-sm btn-outline" style={{ height: 40, padding: '0 16px', fontSize: 13 }} onClick={onCancel}>취소</button>
        <button type="button" className="btn btn-sm btn-primary" style={{ height: 40, padding: '0 18px', fontSize: 13, boxShadow: '0 3px 0 var(--blue-dark)' }} onClick={save} disabled={saving}>
          {saving ? '저장 중…' : '저장'}
        </button>
      </div>
    </div>
  );
}
