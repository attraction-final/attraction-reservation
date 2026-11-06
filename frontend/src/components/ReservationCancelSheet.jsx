import { useState } from 'react';
import { reservationApi } from '../api/index.js';

/*
 * 예약 취소 확인창 (아래에서 올라오는 시트)
 * "내 예약" 목록과 "예약 상세" 두 화면에서 같이 써요. 그래서 따로 뺐어요.
 *
 * props
 *  - reservation : { id, attractionName, time } (time 은 "14:30" 같은 시작 시간)
 *  - onClose     : 닫기 눌렀을 때
 *  - onDone      : 취소에 성공했을 때 (부모가 목록을 새로고침하거나 화면을 다시 불러와요)
 */
export default function ReservationCancelSheet({ reservation, onClose, onDone }) {
  const [error, setError] = useState('');
  const [working, setWorking] = useState(false);

  async function confirm() {
    setError('');
    setWorking(true);
    try {
      await reservationApi.cancel(reservation.id);
      onDone();
    } catch (e) {
      setError(e.message); // 예) 이미 지난 예약이라 취소할 수 없을 때 서버 메시지
      setWorking(false);
    }
  }

  const close = () => { if (!working) onClose(); };

  return (
    <div className="sheet-backdrop" onClick={close}>
      <div className="sheet" role="dialog" aria-label="예약 취소 확인" onClick={(e) => e.stopPropagation()}>
        <span className="display" style={{ fontSize: 21 }}>예약을 취소할까요?</span>
        <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
          {reservation.attractionName} · {reservation.time} · 취소 후 좌석이 다른 이용객에게 돌아갑니다. 입장권 환불은 되지 않습니다.
        </span>
        {error && <span className="error-text" role="alert">{error}</span>}
        <div className="row gap-10" style={{ marginTop: 6 }}>
          <button type="button" onClick={close} disabled={working} className="bold"
            style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>
            닫기
          </button>
          <button type="button" onClick={confirm} disabled={working} className="bold"
            style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15,
              boxShadow: '0 4px 0 #8F3D0E', opacity: working ? 0.6 : 1 }}>
            {working ? '취소 중…' : '예약 취소'}
          </button>
        </div>
      </div>
    </div>
  );
}
