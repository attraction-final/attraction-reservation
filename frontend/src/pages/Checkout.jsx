import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import * as PortOne from '@portone/browser-sdk/v2';
import Icon from '../components/Icon.jsx';
import { BackHeader, Badge, ErrorBox, Loading } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { orderApi, paymentApi } from '../api/index.js';
import { ORDER_STATUS, PAYMENT_METHODS, formatDate, formatPrice } from '../api/labels.js';

// 화면의 결제 수단 코드 → 포트원 V2 의 payMethod 값
// 값 이름은 SDK 타입에 있는 것과 같아요. 다만 테스트 채널(PG사)이 그 수단을 지원하는지는 따로 확인해야 해요.
const PORTONE_PAY_METHOD = {
  CARD: 'CARD',
  EASY_PAY: 'EASY_PAY',
  BANK_TRANSFER: 'TRANSFER',
};

export default function Checkout() {
  const { orderId } = useParams();
  const navigate = useNavigate();

  // Tickets 화면이 navigate(..., { state }) 로 넣어 준 "가방" (방문일)
  // 방문일은 주문 상세 응답에 없어서 가방에서만 받을 수 있어요. 새로고침하면 '-' 로 보여요.
  const { state } = useLocation();
  const visitDate = state?.date;

  const { data: order, loading, error, reload } = useApi(() => orderApi.detail(orderId), [orderId]);

  // 결제번호(pgId)는 주문 상세 응답에서 받아요 → 새로고침하거나 결제 내역에서 들어와도 결제할 수 있어요.
  const pgId = order?.pgId ?? state?.pgId;

  const [method, setMethod] = useState(PAYMENT_METHODS[0]?.code ?? 'CARD');
  const [agreed, setAgreed] = useState(false);
  const [payError, setPayError] = useState('');
  const [paying, setPaying] = useState(false);

  async function pay() {
    setPayError('');
    if (!pgId) {
      setPayError('결제 정보가 없어요. 잠시 뒤 다시 시도해 주세요.');
      return;
    }
    setPaying(true);
    try {
      // ① 서버에서 포트원 상점 정보 받기
      const { storeId, channelKey } = await paymentApi.config();

      // ② 포트원 결제창 띄우기 (paymentId 자리에 서버가 만들어 준 pgId 를 넣어요)
      const res = await PortOne.requestPayment({
        storeId,
        channelKey,
        paymentId: pgId,
        orderName: `${order.productName} × ${order.quantity}`,
        totalAmount: order.totalPrice,
        currency: 'KRW', // @portone/browser-sdk 0.1.13 의 Currency 타입 기준 원화 = 'KRW'
        payMethod: PORTONE_PAY_METHOD[method] ?? 'CARD',
        // 모바일은 대부분 결제사 페이지로 "이동"했다가 돌아오는 방식이에요.
        // 그때 돌아올 주소예요. PC 팝업 결제에서는 쓰이지 않고 아래 ③④가 그대로 실행돼요.
        redirectUrl: `${window.location.origin}/checkout/${orderId}/complete`,
      });

      // ③ 사용자가 결제창을 닫았거나 결제가 실패하면 res.code 에 오류 코드가 들어와요.
      if (res?.code != null) {
        throw new Error(res.message || '결제가 취소되었거나 실패했어요.');
      }

      // ④ 서버에 승인 요청 → 서버가 포트원에 금액을 다시 확인하고 주문을 "결제 완료"로 바꿔요.
      await paymentApi.confirm(pgId, orderId);
      navigate('/my/tickets');
    } catch (e) {
      setPayError(e.message);
    } finally {
      setPaying(false);
    }
  }

  // 결제 버튼 글자: 상황에 따라 바뀌어요.
  function buttonText() {
    if (order.status !== 'PENDING') return '이미 처리된 주문이에요';
    if (!pgId) return '결제 정보를 불러오지 못했어요';
    if (paying) return '결제 중…';
    return `${formatPrice(order.totalPrice)} 결제하기`;
  }

  return (
    <div className="screen">
      <BackHeader title="주문 · 결제" to="/tickets" />
      {loading && <Loading />}
      {error && <ErrorBox error={error} onRetry={reload} />}
      {order && (
        <>
          <div className="screen-body pad stack gap-16">
            {/* 주문 정보 카드 */}
            <div className="card" style={{ borderRadius: 22, overflow: 'hidden', flexShrink: 0 }}>
              <div className="row between" style={{ background: 'var(--blue)', padding: '14px 16px' }}>
                <span className="display" style={{ fontSize: 18, color: '#fff' }}>주문 정보</span>
                <Badge status={ORDER_STATUS[order.status]} />
              </div>
              <div className="stack gap-10" style={{ padding: '14px 16px', fontSize: 14 }}>
                <Row label="주문번호" value={order.id} />
                <Row label="이용 날짜" value={visitDate ? formatDate(visitDate) : '-'} />
                <Row label={`${order.productName} × ${order.quantity}`} value={`${formatPrice(order.unitPrice)} × ${order.quantity}`} />
              </div>
              <div style={{ borderTop: '2px dashed var(--line)', margin: '0 16px' }} />
              <div className="stack gap-4" style={{ padding: '14px 16px 16px' }}>
                <div className="row between">
                  <span className="bold" style={{ fontSize: 15 }}>결제 금액</span>
                  <span className="display" style={{ fontSize: 22, color: 'var(--blue-ink)' }}>{formatPrice(order.totalPrice)}</span>
                </div>
                <span className="small muted">주문 시점 가격으로 저장됩니다.</span>
              </div>
            </div>

            {/* 결제 수단 */}
            <div className="stack gap-10">
              <h2 className="display" style={{ fontSize: 18 }}>결제 수단</h2>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 8 }}>
                {PAYMENT_METHODS.map((m) => {
                  const on = method === m.code;
                  return (
                    <button key={m.code} type="button" aria-pressed={on} onClick={() => setMethod(m.code)}
                      style={{ height: 54, borderRadius: 16, fontSize: 14, fontWeight: 700, ...(on ? { background: '#F2F7FF', border: '2px solid var(--blue)', color: 'var(--blue-ink)' } : { background: '#fff', border: '2px solid #fff', boxShadow: 'var(--shadow)' }) }}>
                      {m.label}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* 환불 안내 */}
            <div className="row gap-10" style={{ background: '#FFF8E1', borderRadius: 20, padding: '14px 16px', alignItems: 'flex-start', color: '#5A3B00' }}>
              <Icon name="info" size={20} strokeWidth={2} color="var(--yellow-ink)" style={{ flexShrink: 0, marginTop: 1 }} />
              <div className="stack gap-4" style={{ fontSize: 13, lineHeight: 1.5 }}>
                <span className="bold">환불 안내</span>
                <span>미사용 입장권은 이용일 전까지 매수 단위로 부분 환불할 수 있습니다.</span>
                <span>어트랙션 예약 취소는 별도 환불 대상이 아닙니다.</span>
              </div>
            </div>

            {/* 동의 체크 */}
            <label className="row gap-10" style={{ fontSize: 14, cursor: 'pointer' }}>
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} style={{ width: 22, height: 22, accentColor: 'var(--blue)' }} />
              주문 내용 및 환불 정책에 동의합니다
            </label>
            {payError && <span className="error-text" role="alert">{payError}</span>}
          </div>

          <div className="bottom-bar">
            <button type="button" className="btn btn-primary" disabled={!agreed || paying || !pgId || order.status !== 'PENDING'} onClick={pay}>
              {buttonText()}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

function Row({ label, value }) {
  return (
    <div className="row between">
      <span className="muted">{label}</span>
      <span>{value}</span>
    </div>
  );
}
