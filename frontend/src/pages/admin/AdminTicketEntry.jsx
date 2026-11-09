import { useEffect, useRef, useState } from 'react';
import { BrowserQRCodeReader } from '@zxing/browser';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge } from '../../components/ui.jsx';
import { adminApi } from '../../api/index.js';
import { TICKET_STATUS, TICKET_TYPE, formatDate } from '../../api/labels.js';

// 이 화면에서 처리한 결과를 몇 개까지 보여 줄지 (새로고침하면 사라지는 화면용 기록이에요)
const HISTORY_MAX = 10;

// 카메라는 1초에 여러 번 같은 QR을 읽어요. 같은 코드는 이 시간(ms) 동안 다시 처리하지 않아요.
// (안 막으면 같은 입장권으로 요청이 여러 번 가서 두 번째부터 "이미 입장" 오류가 떠요)
const SAME_CODE_IGNORE_MS = 5000;

/**
 * 관리자 입장 처리 화면 (놀이공원 입구 게이트 역할)
 *
 * 1) 회원이 "내 입장권" 화면의 입장 코드를 보여 줘요.
 * 2) 관리자가 그 코드를 입력하고 "입장 처리"를 눌러요.
 *    또는 "카메라로 스캔"을 켜고 회원의 QR 코드를 비추면 자동으로 입장 처리돼요.
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
  const [scanning, setScanning] = useState(false); // 카메라 스캔 화면을 켰는지

  // busy 를 state 로만 보면, 카메라가 아주 빠르게 연달아 읽을 때 아직 바뀌기 전 값을 볼 수 있어요.
  // 그래서 "지금 요청 중인지"는 ref 로도 같이 들고 있어요. (ref 는 바로 바뀌어요)
  const busyRef = useRef(false);
  const lastScanRef = useRef({ code: '', at: 0 }); // 마지막으로 스캔한 코드와 시각

  // 입장 처리 본체: 직접 입력(submit)과 카메라 스캔(handleScan)이 같이 써요.
  async function enter(rawCode) {
    const trimmed = rawCode.trim();
    if (!trimmed) {
      setError('입장 코드를 입력해 주세요.');
      return;
    }
    if (busyRef.current) return; // 이미 처리 중이면 무시
    busyRef.current = true;
    setBusy(true);
    setError('');
    try {
      const result = await adminApi.enterTicket(trimmed);
      setHistory((prev) => [result, ...prev].slice(0, HISTORY_MAX));
      setCode(''); // 다음 손님 코드를 바로 입력할 수 있게 비워요
    } catch (err) {
      setError(err.message);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  // 직접 입력 후 "입장 처리" 버튼
  function submit(e) {
    e.preventDefault();
    enter(code);
  }

  // 카메라가 QR 을 읽었을 때
  function handleScan(text) {
    const now = Date.now();
    const last = lastScanRef.current;
    if (text === last.code && now - last.at < SAME_CODE_IGNORE_MS) return; // 방금 읽은 코드면 무시
    lastScanRef.current = { code: text, at: now };
    setCode(text); // 어떤 코드를 읽었는지 입력칸에도 보여 줘요
    enter(text);
  }

  const latest = history[0];

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="입장 처리">
          <form className="stack gap-8" onSubmit={submit}>
            {/* 카메라 스캔: 켜져 있는 동안 계속 읽어요. 다음 손님도 그대로 비추면 돼요 */}
            {scanning
              ? <QrScanner onScan={handleScan} onClose={() => setScanning(false)} />
              : (
                <button type="button" className="btn btn-outline" style={{ height: 50 }} onClick={() => setScanning(true)}>
                  카메라로 QR 스캔
                </button>
              )}
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
              회원의 &ldquo;내 입장권&rdquo; 화면에 있는 QR 코드를 스캔하거나 입장 코드를 입력해 주세요.
            </span>
          )}
        </div>
      </div>
      <AdminTabBar />
    </div>
  );
}

// 카메라 영상에서 QR 코드를 계속 읽는 상자
// - 처음 켤 때 브라우저가 카메라 권한을 물어봐요.
// - 카메라는 https 주소나 localhost 에서만 켜져요. (브라우저 보안 규칙)
// - 이 상자가 화면에서 사라지면(닫기, 다른 화면으로 이동) 카메라도 꺼요.
function QrScanner({ onScan, onClose }) {
  const videoRef = useRef(null);
  const [camError, setCamError] = useState('');

  // 부모가 다시 그려질 때마다 onScan 함수가 새로 만들어져요.
  // 카메라는 한 번만 켜고, "읽었을 때 부를 함수"만 최신 것으로 바꿔 끼워요.
  const onScanRef = useRef(onScan);
  onScanRef.current = onScan;

  useEffect(() => {
    let controls = null;   // 카메라를 끄는 리모컨 (켜지면 받아요)
    let cancelled = false; // 카메라가 켜지기 전에 상자가 닫혔는지

    const reader = new BrowserQRCodeReader();
    reader
      .decodeFromConstraints(
        // environment = 휴대폰 뒷면 카메라를 먼저 시도. 노트북처럼 앞 카메라만 있으면 그걸 써요.
        { video: { facingMode: 'environment' } },
        videoRef.current,
        (result) => {
          // QR 을 못 찾은 순간에도 계속 불려요. 읽었을 때(result 가 있을 때)만 처리해요.
          if (result) onScanRef.current(result.getText());
        },
      )
      .then((c) => {
        if (cancelled) c.stop(); // 켜지는 사이에 이미 닫혔으면 바로 꺼요
        else controls = c;
      })
      .catch((err) => setCamError(cameraErrorMessage(err)));

    // 상자가 사라질 때 카메라 끄기
    return () => {
      cancelled = true;
      if (controls) controls.stop();
    };
  }, []);

  return (
    <div className="stack gap-8">
      {camError
        ? <span className="error-text" role="alert"><Icon name="alert" size={14} strokeWidth={2} />{camError}</span>
        : (
          <video
            ref={videoRef}
            muted
            playsInline
            style={{ width: '100%', aspectRatio: '1 / 1', objectFit: 'cover', borderRadius: 16, background: '#000' }}
          />
        )}
      <span className="small muted">QR 코드를 화면 가운데에 비춰 주세요. 읽히면 바로 입장 처리돼요.</span>
      <button type="button" className="btn btn-sm btn-outline" style={{ alignSelf: 'center' }} onClick={onClose}>
        스캔 닫기
      </button>
    </div>
  );
}

// 카메라 오류를 직원이 이해할 수 있는 문장으로 바꿔요.
function cameraErrorMessage(err) {
  if (err?.name === 'NotAllowedError') return '카메라 권한이 거부됐어요. 주소창 왼쪽 자물쇠 아이콘에서 카메라를 허용해 주세요.';
  if (err?.name === 'NotFoundError') return '카메라를 찾지 못했어요. 아래 칸에 입장 코드를 직접 입력해 주세요.';
  if (err?.name === 'NotReadableError') return '다른 프로그램이 카메라를 쓰고 있어요. 그 프로그램을 끄고 다시 시도해 주세요.';
  if (!window.isSecureContext) return '카메라는 https 주소에서만 쓸 수 있어요. 입장 코드를 직접 입력해 주세요.';
  return '카메라를 켜지 못했어요. 입장 코드를 직접 입력해 주세요.';
}
