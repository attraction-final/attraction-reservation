import { useState } from 'react';
import { Link } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackButton, Badge, Empty, ErrorBox, Loading, TabBar } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { ticketApi } from '../api/index.js';
import { TICKET_STATUS, TICKET_TYPE, formatDate, formatPrice } from '../api/labels.js';

// 환불 사유 최대 글자 수 (백엔드 CreatePartialRefundRequest 의 @Size(max = 200) 과 같아요)
const REASON_MAX = 200;

const FILTERS = [
  { code: 'ALL', label: '전체' },
  { code: 'USABLE', label: '사용 가능' },
  { code: 'PAST', label: '지난 입장권' },
];

export default function MyTickets() {
  const [scope, setScope] = useState('ALL');
  const { data, loading, error, reload } = useApi(() => ticketApi.mine(scope), [scope]);
  const [message, setMessage] = useState('');

  // 환불 확인창 상태
  const [target, setTarget] = useState(null);       // 환불하려고 고른 입장권 (null 이면 창 닫힘)
  const [reason, setReason] = useState('');         // 환불 사유
  const [refundError, setRefundError] = useState('');
  const [refunding, setRefunding] = useState(false); // 요청 중 버튼 두 번 누르기 방지

  // 맨 위 큰 카드: 사용할 수 있는 입장권 중 첫 번째
  const featured = data?.find((t) => t.status === 'UNUSED' || t.status === 'IN_USE');
  const rest = (data || []).filter((t) => t !== featured);

  // 환불 요청 버튼 → 확인창 열기
  function openRefund(t) {
    setTarget(t);
    setReason('');
    setRefundError('');
  }

  function closeRefund() {
    if (refunding) return; // 요청 중에는 못 닫게
    setTarget(null);
  }

  // 확인창의 "환불 요청" 버튼
  async function confirmRefund() {
    const trimmed = reason.trim();
    if (!trimmed) {
      setRefundError('환불 사유를 입력해 주세요.');
      return;
    }
    setRefunding(true);
    setRefundError('');
    try {
      const result = await ticketApi.refund(target, trimmed);
      // 환불은 서버가 PG(포트원)에 취소를 요청하는 단계가 있어서 바로 끝나지 않을 수 있어요.
      setMessage(
        result?.status === 'COMPLETED'
          ? `환불이 완료됐어요. (${formatPrice(result.refundAmount)})`
          : `환불 요청을 접수했어요. 처리까지 시간이 조금 걸릴 수 있어요. (${formatPrice(result?.refundAmount)})`
      );
      setTarget(null);
      reload();
    } catch (e) {
      // 환불 기한 지남, 이미 사용한 입장권 등 서버가 보낸 이유를 그대로 보여 줘요.
      setRefundError(e.message);
    } finally {
      setRefunding(false);
    }
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="stack gap-12" style={{ padding: '18px 16px 10px' }}>
          <div className="row between">
            <div className="title-with-back">
              <BackButton to="/" />
              <h1 className="h1">내 입장권</h1>
            </div>
            <Link to="/my" className="bold" style={{ fontSize: 13, textDecoration: 'none' }}>결제 내역</Link>
          </div>
          <div className="row gap-8">
            {FILTERS.map((f) => (
              <button key={f.code} type="button" className={`chip${scope === f.code ? ' on' : ''}`} aria-pressed={scope === f.code} onClick={() => setScope(f.code)} style={{ height: 38, padding: '0 16px' }}>
                {f.label}
              </button>
            ))}
          </div>
        </div>

        <div className="pad stack gap-10" style={{ paddingTop: 4 }}>
          {message && <div className="toast">{message}</div>}
          {loading && <Loading />}
          {error && <ErrorBox error={error} onRetry={reload} />}
          {data?.length === 0 && <Empty>입장권이 없어요.<br /><Link to="/tickets">입장권 구매하러 가기</Link></Empty>}

          {featured && (
            <div className="card" style={{ borderRadius: 24, overflow: 'hidden', boxShadow: 'var(--shadow-strong)' }}>
              <div className="row between" style={{ height: 52, background: 'var(--blue)', padding: '0 14px' }}>
                <span className="display" style={{ background: 'rgba(255,255,255,0.95)', borderRadius: 12, padding: '4px 12px', fontSize: 20 }}>{TICKET_TYPE[featured.type]}</span>
                <Badge status={TICKET_STATUS[featured.status]} />
              </div>
              <div className="stack gap-10" style={{ padding: '12px 14px' }}>
                <div className="stack gap-4" style={{ fontSize: 13 }}>
                  <span className="bold">{formatDate(featured.date)}</span>
                  <span className="muted">입장권번호 {featured.id}</span>
                  <span className="muted">소유자 {featured.ownerNickname}</span>
                  <span className="muted">입장 가능 {featured.type === 'AFTERNOON' ? '15:00' : '개장'} ~ 폐장</span>
                  {featured.orderId && <span className="muted">주문번호 {featured.orderId}</span>}
                </div>
                {/* 미사용: 입구에서 보여 줄 입장 코드 / 사용중: 이미 입장 완료 */}
                {featured.status === 'UNUSED'
                  ? <EntryCode code={featured.code} />
                  : <span className="small" style={{ color: 'var(--blue-ink)' }}>입장 처리가 끝났어요. 이제 어트랙션을 예약할 수 있어요.</span>}
              </div>
              <div style={{ borderTop: '2px dashed var(--line)', margin: '0 14px' }} />
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
                <Link to="/attractions" className="row bold" style={{ height: 50, justifyContent: 'center', fontSize: 14, textDecoration: 'none' }}>어트랙션 예약</Link>
                {/* 미사용(UNUSED)이고 결제번호(pgId)가 있을 때만 환불할 수 있어요 */}
                <button type="button" className="bold" disabled={!canRefund(featured)} onClick={() => openRefund(featured)}
                  style={{ height: 50, border: 0, background: 'transparent', fontSize: 14, color: canRefund(featured) ? 'var(--orange-ink)' : '#AEB8CB' }}>
                  환불 요청
                </button>
              </div>
            </div>
          )}

          {rest.map((t) => {
            const active = t.status === 'UNUSED' || t.status === 'IN_USE';
            return (
              <div key={t.id} className="stack gap-10" style={{ padding: '12px 14px', borderRadius: 18, ...(active ? { background: '#fff', boxShadow: 'var(--shadow)' } : { background: 'rgba(255,255,255,0.6)', border: '1.5px solid #DCE5F0' }) }}>
                <div className="row gap-12">
                  <span className="icon-circle" style={{ width: 38, height: 38, borderRadius: 12, ...(active ? { background: 'var(--yellow-soft)', color: 'var(--yellow-ink)' } : { background: 'var(--soft)', color: 'var(--subtle)' }) }}>
                    <Icon name="ticket" size={20} />
                  </span>
                  <span className="stack grow" style={{ gap: 1 }}>
                    <span className="bold" style={{ fontSize: 15 }}>{TICKET_TYPE[t.type]}</span>
                    <span className="small muted">{formatDate(t.date, false)} · {t.id}</span>
                  </span>
                  <Badge status={TICKET_STATUS[t.status]} />
                </div>
                {/* 여러 장을 산 경우: 두 번째 미사용 입장권부터도 코드가 보여야 입장할 수 있어요 */}
                {t.status === 'UNUSED' && <EntryCode code={t.code} />}
              </div>
            );
          })}
        </div>
      </div>
      {/* 아래에서 올라오는 환불 확인창 (예약 취소 확인창과 같은 모양) */}
      {target && (
        <div className="sheet-backdrop" onClick={closeRefund}>
          <div className="sheet" role="dialog" aria-label="입장권 환불 요청" onClick={(e) => e.stopPropagation()}>
            <span className="display" style={{ fontSize: 21 }}>입장권을 환불할까요?</span>
            <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
              {TICKET_TYPE[target.type]} · {formatDate(target.date)} · 입장권번호 {target.id}
              <br />환불하면 이 입장권은 다시 사용할 수 없어요. 어트랙션 예약도 함께 확인해 주세요.
            </span>

            <div className="field">
              <label htmlFor="refund-reason">환불 사유</label>
              <textarea
                id="refund-reason"
                className={`input${refundError ? ' error' : ''}`}
                value={reason}
                maxLength={REASON_MAX}
                onChange={(e) => setReason(e.target.value)}
                placeholder="예) 일정이 바뀌었어요"
                rows={3}
                style={{ height: 'auto', padding: '12px 16px', resize: 'none', fontFamily: 'inherit' }}
              />
              <span className="small muted" style={{ textAlign: 'right' }}>{reason.length} / {REASON_MAX}</span>
            </div>

            {refundError && <span className="error-text" role="alert">{refundError}</span>}

            <div className="row gap-10" style={{ marginTop: 6 }}>
              <button type="button" onClick={closeRefund} disabled={refunding} className="bold"
                style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>
                닫기
              </button>
              <button type="button" onClick={confirmRefund} disabled={refunding} className="bold"
                style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15, boxShadow: '0 4px 0 #8F3D0E', opacity: refunding ? 0.6 : 1 }}>
                {refunding ? '요청 중…' : '환불 요청'}
              </button>
            </div>
          </div>
        </div>
      )}
      <TabBar />
    </div>
  );
}

// 환불 가능한 입장권인지: 미사용 + 결제번호가 있어야 해요.
// (환불 기한 같은 나머지 규칙은 서버가 검사하고, 안 되면 이유를 오류 메시지로 보내 줘요)
function canRefund(t) {
  return t.status === 'UNUSED' && Boolean(t.pgId);
}

// 입장 코드 상자: 입구(관리자 입장 처리 화면)에서 이 코드를 입력하면 입장이 처리돼요.
// QR 코드는 나중에 이 code 값으로 그리면 돼요 (예: qrcode.react 라이브러리).
function EntryCode({ code }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // 복사가 막힌 환경(http 주소 등)에서는 직접 길게 눌러 복사하면 돼요.
      setCopied(false);
    }
  }

  if (!code) return null;
  return (
    <div className="row between gap-8" style={{ background: 'var(--soft)', borderRadius: 12, padding: '8px 10px' }}>
      <span className="stack" style={{ gap: 2, minWidth: 0 }}>
        <span className="small muted">입장 코드 (입구에서 보여 주세요)</span>
        <span style={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 12, wordBreak: 'break-all', userSelect: 'all' }}>{code}</span>
      </span>
      <button type="button" className="btn btn-sm btn-outline" onClick={copy} style={{ flexShrink: 0 }}>
        {copied ? '복사됨' : '복사'}
      </button>
    </div>
  );
}
