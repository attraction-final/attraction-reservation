import { useState } from 'react';
import Icon from '../../components/Icon.jsx';
import { AdminHeader, AdminTabBar, Badge, Empty, ErrorBox, Loading } from '../../components/ui.jsx';
import { useApi } from '../../hooks/useApi.js';
import { adminApi } from '../../api/index.js';
import { ORDER_STATUS, TICKET_STATUS, formatPrice, shortDate } from '../../api/labels.js';

// 상태 필터: 백엔드 OrderStatus 다섯 개 (labels.js 의 ORDER_STATUS 순서 그대로)
const FILTERS = [{ code: '', label: '전체' }, ...Object.entries(ORDER_STATUS).map(([code, v]) => ({ code, label: v.label }))];

// 환불 사유 최대 글자 수 (백엔드 CreateRefundRequest 의 @Size(max = 200))
const REASON_MAX = 200;

// 전체·부분 환불 버튼을 보여 줄 주문 상태: 결제 완료, 부분 환불
const REFUNDABLE = ['COMPLETED', 'PARTIALLY_REFUNDED'];

export default function AdminOrders() {
  // 검색: 회원번호(userId) 입력 → 검색 버튼을 눌렀을 때만 반영
  const [userIdInput, setUserIdInput] = useState('');
  const [userId, setUserId] = useState('');
  const [status, setStatus] = useState('');
  const { data, loading, error, reload } = useApi(() => adminApi.orders({ status, userId }), [status, userId]);

  const [openId, setOpenId] = useState(null);   // 펼쳐 둔 주문
  const [details, setDetails] = useState({});   // 주문번호 → 상세 정보 (한 번 불러오면 저장)
  const [detailError, setDetailError] = useState('');
  const [message, setMessage] = useState('');
  const [selectedTicketIds, setSelectedTicketIds] = useState([]); // 부분 환불하려고 체크한 입장권 번호들

  // 환불 확인창
  // refundTarget = { detail: 주문 상세, mode: 'full' | 'partial', ticketIds: [...] }
  const [refundTarget, setRefundTarget] = useState(null);
  const [reason, setReason] = useState('');
  const [refundError, setRefundError] = useState('');
  const [busy, setBusy] = useState(false);                 // 요청 중 버튼 두 번 누르기 방지

  // 카드를 펼치면 상세(주문 상품·결제번호)를 불러와요.
  async function toggle(order) {
    if (openId === order.id) {
      setOpenId(null);
      return;
    }
    setOpenId(order.id);
    setDetailError('');
    setSelectedTicketIds([]); // 다른 주문을 펼치면 체크를 비워요
    if (details[order.id]) return; // 이미 불러온 적 있으면 다시 안 불러요
    try {
      const d = await adminApi.orderDetail(order.id);
      setDetails((prev) => ({ ...prev, [order.id]: d }));
    } catch (e) {
      setDetailError(e.message);
    }
  }

  // 주문 상태가 바뀌었으니 목록과 저장해 둔 상세를 새로 받아요.
  function refresh(orderId) {
    setDetails((prev) => {
      const next = { ...prev };
      delete next[orderId];
      return next;
    });
    setOpenId(null);
    setSelectedTicketIds([]);
    reload();
  }

  async function cancelOrder(order) {
    if (!window.confirm(`주문번호 ${order.id} 주문을 취소할까요?`)) return;
    setMessage('');
    setBusy(true);
    try {
      await adminApi.cancelOrder(order.id);
      setMessage(`주문번호 ${order.id} 주문을 취소했어요. 재고도 다시 채워졌어요.`);
      refresh(order.id);
    } catch (e) {
      setMessage(e.message);
    } finally {
      setBusy(false);
    }
  }

  // 입장권 체크박스를 누르면 목록에 넣거나 빼요.
  function toggleTicket(ticketId) {
    setSelectedTicketIds((prev) =>
      prev.includes(ticketId) ? prev.filter((id) => id !== ticketId) : [...prev, ticketId]
    );
  }

  // mode: 'full'(전체 환불) 또는 'partial'(고른 입장권만 환불)
  function openRefund(detail, mode) {
    setRefundTarget({ detail, mode, ticketIds: mode === 'partial' ? selectedTicketIds : [] });
    setReason('');
    setRefundError('');
  }

  function closeRefund() {
    if (busy) return;
    setRefundTarget(null);
  }

  async function confirmRefund() {
    const trimmed = reason.trim();
    if (!trimmed) {
      setRefundError('환불 사유를 입력해 주세요.');
      return;
    }
    const { detail, mode, ticketIds } = refundTarget;
    setBusy(true);
    setRefundError('');
    try {
      const result = mode === 'partial'
        ? await adminApi.refundOrderPartial(detail.pgId, ticketIds, trimmed)
        : await adminApi.refundOrder(detail.pgId, trimmed);
      const what = mode === 'partial' ? `입장권 ${ticketIds.length}매 환불` : '환불';
      // 서버가 포트원에 취소를 요청하는 단계가 있어서 바로 끝나지 않을 수 있어요.
      setMessage(
        result?.status === 'COMPLETED'
          ? `주문번호 ${detail.id} ${what}이 완료됐어요. (${formatPrice(result.refundAmount)})`
          : `주문번호 ${detail.id} ${what} 요청을 접수했어요. (상태: ${result?.status ?? '-'})`
      );
      setRefundTarget(null);
      refresh(detail.id);
    } catch (e) {
      // 환불 기한 지남, 미사용 입장권 없음 등 서버가 보낸 이유를 그대로 보여 줘요.
      setRefundError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <AdminHeader title="주문 · 환불" right={<span className="small muted">검색 결과 {data?.length ?? 0}건</span>}>
          <form className="row gap-8" onSubmit={(e) => { e.preventDefault(); setUserId(userIdInput.trim()); }}>
            <div className="search grow" style={{ height: 44, borderRadius: 14, padding: '0 12px' }}>
              <Icon name="search" size={18} strokeWidth={2} color="var(--muted)" />
              {/* 백엔드 검색 조건이 회원번호(userId)뿐이라 숫자만 받아요 */}
              <input type="search" inputMode="numeric" aria-label="회원번호 검색" placeholder="회원번호"
                value={userIdInput} onChange={(e) => setUserIdInput(e.target.value.replace(/\D/g, ''))} style={{ fontSize: 14 }} />
            </div>
            <button type="submit" className="btn btn-sm btn-dark">검색</button>
          </form>
          <div className="row gap-8" style={{ overflowX: 'auto', paddingBottom: 8 }}>
            {FILTERS.map((f) => (
              <button key={f.code || 'all'} type="button" className={`chip${status === f.code ? ' on' : ''}`} onClick={() => setStatus(f.code)}
                style={{ height: 34, padding: '0 12px', fontSize: 12, flexShrink: 0 }}>
                {f.label}
              </button>
            ))}
          </div>
        </AdminHeader>

        <div className="pad stack gap-10" style={{ paddingTop: 14 }}>
          {message && <div className="toast">{message}</div>}
          {loading && <Loading />}
          {error && <ErrorBox error={error} onRetry={reload} />}
          {data?.length === 0 && <Empty>조건에 맞는 주문이 없어요.</Empty>}

          {data?.map((o) => {
            const open = o.id === openId;
            const d = details[o.id];
            return (
              <div key={o.id} style={{ borderRadius: 20, overflow: 'hidden', background: '#fff', ...(open ? { border: '2px solid var(--blue)', boxShadow: 'var(--shadow-strong)' } : { border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                {/* 카드 윗부분: 누르면 펼쳐져요 */}
                <button type="button" aria-expanded={open} onClick={() => toggle(o)} className="row gap-12"
                  style={{ width: '100%', padding: 12, border: 0, background: 'transparent', textAlign: 'left' }}>
                  <span className="icon-circle" style={{ width: 44, height: 44, borderRadius: 14, background: 'var(--yellow-soft)', color: 'var(--yellow-ink)' }}>
                    <Icon name="receipt" size={22} />
                  </span>
                  <span className="stack grow" style={{ gap: 2 }}>
                    <span className="row between gap-8">
                      <span className="bold" style={{ fontSize: 14 }}>주문번호 {o.id}</span>
                      <Badge status={ORDER_STATUS[o.status]} small />
                    </span>
                    <span style={{ fontSize: 13 }}>{o.nickname} · 방문 {o.visitDate ? shortDate(o.visitDate) : '-'}</span>
                    <span className="row between">
                      <span className="small muted">{o.createdAt}</span>
                      <span className="bold" style={{ fontSize: 14 }}>{formatPrice(o.totalPrice)}</span>
                    </span>
                  </span>
                </button>

                {/* 펼친 부분: 상세 정보와 처리 버튼 */}
                {open && (
                  <div className="stack gap-10" style={{ borderTop: '1.5px dashed var(--line)', margin: '0 12px', padding: '12px 0' }}>
                    {!d && !detailError && <Loading />}
                    {detailError && <span className="error-text" role="alert">{detailError}</span>}
                    {d && (
                      <>
                        <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '6px 12px', fontSize: 13 }}>
                          <dt className="muted">회원</dt>
                          <dd style={{ margin: 0, textAlign: 'right' }}>{d.nickname} ({d.email}) · #{d.userId}</dd>
                          <dt className="muted">방문일</dt>
                          <dd style={{ margin: 0, textAlign: 'right' }}>{d.visitDate ?? '-'}</dd>
                          <dt className="muted">주문 일시</dt>
                          <dd style={{ margin: 0, textAlign: 'right' }}>{d.createdAt}</dd>
                          <dt className="muted">결제번호</dt>
                          <dd style={{ margin: 0, textAlign: 'right', wordBreak: 'break-all' }}>{d.pgId ?? '-'}</dd>
                        </dl>

                        {/* 주문 상품 목록 */}
                        <div className="stack gap-4" style={{ background: 'var(--soft)', borderRadius: 14, padding: '10px 12px', fontSize: 13 }}>
                          {d.items.map((i) => (
                            <div key={i.id} className="row between">
                              <span>{i.productName} × {i.quantity}</span>
                              <span>{formatPrice(i.price * i.quantity)}</span>
                            </div>
                          ))}
                          <div className="row between bold" style={{ borderTop: '1px solid var(--line)', paddingTop: 6, marginTop: 2 }}>
                            <span>합계</span><span>{formatPrice(d.totalPrice)}</span>
                          </div>
                        </div>

                        {/* 발급된 입장권 목록: 결제 완료·부분 환불 주문의 "미사용" 입장권만 고를 수 있어요 */}
                        {d.tickets.length > 0 && (
                          <div className="stack gap-6">
                            <span className="bold" style={{ fontSize: 13 }}>입장권 {d.tickets.length}매</span>
                            {d.tickets.map((t) => {
                              const selectable = REFUNDABLE.includes(d.status) && !!d.pgId && t.status === 'UNUSED';
                              return (
                                <label key={t.id} className="row between gap-8"
                                  style={{ padding: '8px 12px', borderRadius: 12, background: 'var(--soft)', fontSize: 13, opacity: selectable ? 1 : 0.55, cursor: selectable ? 'pointer' : 'default' }}>
                                  <span className="row gap-8">
                                    <input type="checkbox" aria-label={`입장권 ${t.id} 선택`}
                                      checked={selectedTicketIds.includes(t.id)}
                                      disabled={!selectable || busy}
                                      onChange={() => toggleTicket(t.id)}
                                      style={{ width: 20, height: 20, accentColor: 'var(--blue)' }} />
                                    <span>#{t.id} {t.name}</span>
                                  </span>
                                  <Badge status={TICKET_STATUS[t.status]} small />
                                </label>
                              );
                            })}
                          </div>
                        )}

                        {/* 결제 대기 주문: 취소만 가능 */}
                        {d.status === 'PENDING' && (
                          <>
                            <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }} disabled={busy} onClick={() => cancelOrder(d)}>
                              주문 취소 처리
                            </button>
                            <span className="small muted">결제 대기 주문만 취소할 수 있어요. 취소하면 상품 재고가 다시 채워져요.</span>
                          </>
                        )}

                        {/* 결제 완료·부분 환불 주문: 부분 환불, 전체 환불 가능 */}
                        {REFUNDABLE.includes(d.status) && (
                          <>
                            <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }}
                              disabled={busy || !d.pgId || selectedTicketIds.length === 0} onClick={() => openRefund(d, 'partial')}>
                              선택한 입장권 {selectedTicketIds.length}매 환불
                            </button>
                            <button type="button" className="btn btn-sm btn-danger-outline" style={{ width: '100%', height: 46 }} disabled={busy || !d.pgId} onClick={() => openRefund(d, 'full')}>
                              전체 환불 처리
                            </button>
                            <span className="small muted">위에서 미사용 입장권을 골라 일부만 환불하거나, 미사용 입장권을 한꺼번에 환불할 수 있어요. 이미 사용한 입장권은 환불되지 않아요.</span>
                          </>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* 아래에서 올라오는 환불 확인창 (회원 화면 환불 창과 같은 모양) */}
      {refundTarget && (
        <div className="sheet-backdrop" onClick={closeRefund}>
          <div className="sheet" role="dialog" aria-label={refundTarget.mode === 'partial' ? '관리자 부분 환불' : '관리자 전체 환불'} onClick={(e) => e.stopPropagation()}>
            <span className="display" style={{ fontSize: 21 }}>
              {refundTarget.mode === 'partial' ? `입장권 ${refundTarget.ticketIds.length}매를 환불할까요?` : '주문을 환불할까요?'}
            </span>
            <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
              주문번호 {refundTarget.detail.id} · {refundTarget.detail.nickname} · {formatPrice(refundTarget.detail.totalPrice)}
              {refundTarget.mode === 'partial' ? (
                <>
                  <br />입장권 {refundTarget.ticketIds.map((id) => `#${id}`).join(', ')}
                  <br />예상 환불 금액 {formatPrice(expectedRefundAmount(refundTarget.detail, refundTarget.ticketIds))} (최종 금액은 서버가 계산해요)
                  <br />환불하면 되돌릴 수 없어요.
                </>
              ) : (
                <><br />미사용 입장권이 모두 환불되고, 되돌릴 수 없어요.</>
              )}
            </span>

            <div className="field">
              <label htmlFor="admin-refund-reason">환불 사유</label>
              <textarea
                id="admin-refund-reason"
                className={`input${refundError ? ' error' : ''}`}
                value={reason}
                maxLength={REASON_MAX}
                onChange={(e) => setReason(e.target.value)}
                placeholder="예) 우천으로 인한 운영 중단"
                rows={3}
                style={{ height: 'auto', padding: '12px 16px', resize: 'none', fontFamily: 'inherit' }}
              />
              <span className="small muted" style={{ textAlign: 'right' }}>{reason.length} / {REASON_MAX}</span>
            </div>

            {refundError && <span className="error-text" role="alert">{refundError}</span>}

            <div className="row gap-10" style={{ marginTop: 6 }}>
              <button type="button" onClick={closeRefund} disabled={busy} className="bold"
                style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>
                닫기
              </button>
              <button type="button" onClick={confirmRefund} disabled={busy} className="bold"
                style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15, boxShadow: '0 4px 0 #8F3D0E', opacity: busy ? 0.6 : 1 }}>
                {busy ? '요청 중…' : '환불 처리'}
              </button>
            </div>
          </div>
        </div>
      )}
      <AdminTabBar />
    </div>
  );
}

// 고른 입장권들의 예상 환불 금액: 입장권이 속한 주문 상품의 "주문 당시 단가"를 더해요.
function expectedRefundAmount(detail, ticketIds) {
  const priceByItemId = new Map(detail.items.map((i) => [i.id, i.price]));
  return detail.tickets
    .filter((t) => ticketIds.includes(t.id))
    .reduce((sum, t) => sum + (priceByItemId.get(t.orderItemId) ?? 0), 0);
}
