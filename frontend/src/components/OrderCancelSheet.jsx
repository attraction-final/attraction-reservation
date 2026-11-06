import { useState } from 'react';
import { orderApi } from '../api/index.js';
import { formatPrice } from '../api/labels.js';

/*
 * 주문 취소 확인창 (아래에서 올라오는 시트)
 * 마이페이지 "결제 내역"과 결제 화면(Checkout) 두 곳에서 같이 써요.
 *
 * 백엔드 규칙 (OrderService.updateOrderByCancel)
 *  - "결제 대기(PENDING)" 주문만 취소할 수 있어요. 아니면 409 "결제 대기 상태의 주문만 취소할 수 있습니다."
 *  - 취소하면 서버가 입장권 상품 재고를 되돌리고, 진행 중이던 결제도 취소 처리해요.
 *  - 이미 결제가 끝난 주문은 "취소"가 아니라 "환불"이에요. (내 입장권 화면)
 *
 * props
 *  - order  : { id, totalPrice }
 *  - onClose: 닫기
 *  - onDone : 취소 성공
 */
export default function OrderCancelSheet({ order, onClose, onDone }) {
  const [error, setError] = useState('');
  const [working, setWorking] = useState(false);

  async function confirm() {
    setError('');
    setWorking(true);
    try {
      await orderApi.cancel(order.id);
      onDone();
    } catch (e) {
      // 예) 그 사이 결제가 끝났거나, 시간이 지나 자동 취소된 경우
      setError(e.message);
      setWorking(false);
    }
  }

  const close = () => { if (!working) onClose(); };

  return (
    <div className="sheet-backdrop" onClick={close}>
      <div className="sheet" role="dialog" aria-label="주문 취소 확인" onClick={(e) => e.stopPropagation()}>
        <span className="display" style={{ fontSize: 21 }}>주문을 취소할까요?</span>
        <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
          주문번호 {order.id}{order.totalPrice != null ? ` · ${formatPrice(order.totalPrice)}` : ''} · 아직 결제하지 않은 주문이라 청구되는 금액은 없어요.
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
            {working ? '취소 중…' : '주문 취소'}
          </button>
        </div>
      </div>
    </div>
  );
}
