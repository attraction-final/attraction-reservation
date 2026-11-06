import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import * as PortOne from '@portone/browser-sdk/v2';
import Icon from '../components/Icon.jsx';
import OrderCancelSheet from '../components/OrderCancelSheet.jsx';
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

  // Tickets 화면이 navigate(..., { state }) 로 넣어 준 "가방"
  const { state } = useLocation();

  const { data: order, loading, error, reload } = useApi(() => orderApi.detail(orderId), [orderId]);

  // 방문일은 주문 상세 응답(서버)에서 먼저 받고, 아직 응답이 오기 전이면 가방 값을 잠깐 써요.
  // → 새로고침하거나 결제 내역의 "결제 이어서 하기"로 들어와도 방문일이 보여요.
  const visitDate = order?.visitDate ?? state?.date;

  // 결제번호(pgId)는 주문 상세 응답에서 받아요 → 새로고침하거나 결제 내역에서 들어와도 결제할 수 있어요.
  const pgId = order?.pgId ?? state?.pgId;

  const [method, setMethod] = useState(PAYMENT_METHODS[0]?.code ?? 'CARD');
  const [agreed, setAgreed] = useState(false);
  const [payError, setPayError] = useState('');
  const [paying, setPaying] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false); // 주문 취소 확인창

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
        // 결제사가 보내 준 원래 메시지(예: "[W002] 사용자 취소")는 개발자용이라
        // 손님에게는 쉬운 안내 문구만 보여 주고, 자세한 내용은 콘솔에만 남겨요.
        console.warn('[결제 실패]', res.code, res.message);
        const friendly = '결제가 완료되지 않았어요. 다시 시도해 주세요.';
        // import.meta.env.DEV: npm run dev 로 띄웠을 때만 true, 배포(build)하면 false 예요.
        // 그래서 개발 중에는 오류 코드까지 같이 보이고, 배포 화면에서는 안 보여요.
        throw new Error(import.meta.env.DEV && res.message ? `${friendly} (${res.message})` : friendly);
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

            {/* 결제 대기일 때만 주문 취소 가능 (결제 중에는 막아요) */}
            {order.status === 'PENDING' && (
              <button type="button" onClick={() => setCancelOpen(true)} disabled={paying}
                style={{ alignSelf: 'center', border: 0, background: 'none', fontSize: 13, color: 'var(--muted)', textDecoration: 'underline', padding: 4 }}>
                이 주문 취소하기
              </button>
            )}
          </div>

          <div className="bottom-bar">
            <button type="button" className="btn btn-primary" disabled={!agreed || paying || !pgId || order.status !== 'PENDING'} onClick={pay}>
              {buttonText()}
            </button>
          </div>
        </>
      )}

      {cancelOpen && order && (
        <OrderCancelSheet
          order={order}
          onClose={() => setCancelOpen(false)}
          // 취소된 주문은 결제할 수 없으니 마이페이지(결제 내역)로 보내요. replace: 뒤로 가기로 다시 오지 않게
          onDone={() => navigate('/my', { replace: true })}
        />
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
