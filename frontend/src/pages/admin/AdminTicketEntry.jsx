import { useState } from 'react';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge } from '../../components/ui.jsx';
import { adminApi } from '../../api/index.js';
import { TICKET_STATUS, TICKET_TYPE, formatDate } from '../../api/labels.js';

// 이 화면에서 처리한 결과를 몇 개까지 보여 줄지 (새로고침하면 사라지는 화면용 기록이에요)
const HISTORY_MAX = 10;

/**
 * 관리자 입장 처리 화면 (놀이공원 입구 게이트 역할)
 *
 * 1) 회원이 "내 입장권" 화면의 입장 코드를 보여 줘요.
 * 2) 관리자가 그 코드를 입력하고 "입장 처리"를 눌러요.
 * 3) 서버가 검증에 성공하면 입장권이 미사용(UNUSED) → 사용중(IN_USE)으로 바뀌어요.
 *    그때부터 회원은 어트랙션 예약과 루트 추천을 할 수 있어요.
 *
 * 서버가 거절하는 경우(메시지는 서버가 보낸 문장을 그대로 보여 줘요)
 *  - 없는 코드
 *  - 이미 입장했거나, 환불·만료된 입장권
 *  - 오늘이 방문일이 아님
 *  - 입장 가능 시간이 아님 (종일권 10:00~22:00, 오후권 15:00~22:00)
 */
export default function AdminTicketEntry() {
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);      // 요청 중 버튼 두 번 누르기 방지
  const [error, setError] = useState('');
  const [history, setHistory] = useState([]);   // 방금 처리한 입장 기록 (최신이 위)

  async function submit(e) {
    e.preventDefault();
    const trimmed = code.trim();
    if (!trimmed) {
      setError('입장 코드를 입력해 주세요.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const result = await adminApi.enterTicket(trimmed);
      setHistory((prev) => [result, ...prev].slice(0, HISTORY_MAX));
      setCode(''); // 다음 손님 코드를 바로 입력할 수 있게 비워요
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const latest = history[0];

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="입장 처리">
          <form className="stack gap-8" onSubmit={submit}>
            <div className="field">
              <label htmlFor="entry-code">입장 코드</label>
              <input
                id="entry-code"
                className={`input${error ? ' error' : ''}`}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder="예) TKT-xxxxxxxx-xxxx-..."
                autoComplete="off"
                autoCapitalize="off"
                spellCheck={false}
                style={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 13 }}
              />
            </div>
            {error && <span className="error-text" role="alert"><Icon name="alert" size={14} strokeWidth={2} />{error}</span>}
            <button type="submit" className="btn btn-primary" disabled={busy} style={{ height: 50 }}>
              {busy ? '처리 중…' : '입장 처리'}
            </button>
            <span className="small muted">방문일 당일, 입장 가능 시간(종일권 10:00~, 오후권 15:00~, 22:00 전)에만 입장할 수 있어요.</span>
          </form>
        </AdminHeader>

        <div className="pad stack gap-10" style={{ paddingTop: 14 }}>
          {/* 방금 처리한 결과: 크게 보여 줘요 */}
          {latest && (
            <div className="card stack gap-6" role="status" style={{ borderRadius: 20, padding: 14, border: '2px solid var(--blue)' }}>
              <span className="row between">
                <span className="bold" style={{ fontSize: 16 }}>입장 완료</span>
                <Badge status={TICKET_STATUS[latest.status]} />
              </span>
              <span style={{ fontSize: 13 }}>
                입장권번호 {latest.id} · {TICKET_TYPE[latest.type] ?? latest.type} · {formatDate(latest.date)}
              </span>
              <span className="small muted">입장 시각 {latest.enteredAt}</span>
            </div>
          )}

          {/* 이전 기록 */}
          {history.length > 1 && (
            <div className="stack gap-6">
              <span className="small muted">이 화면에서 처리한 기록 (새로고침하면 사라져요)</span>
              {history.slice(1).map((h) => (
                <div key={`${h.id}-${h.enteredAt}`} className="row between" style={{ padding: '10px 12px', borderRadius: 14, background: '#fff', boxShadow: 'var(--shadow)', fontSize: 13 }}>
                  <span>#{h.id} {TICKET_TYPE[h.type] ?? h.type}</span>
                  <span className="muted">{h.enteredAt}</span>
                </div>
              ))}
            </div>
          )}

          {history.length === 0 && (
            <span className="small muted" style={{ textAlign: 'center', padding: '20px 0' }}>
              회원의 &ldquo;내 입장권&rdquo; 화면에 있는 입장 코드를 입력해 주세요.
            </span>
          )}
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}
