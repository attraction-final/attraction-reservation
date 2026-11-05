import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader, Loading } from '../components/ui.jsx';
import { paymentApi } from '../api/index.js';

/*
 * 모바일 결제가 끝나고 돌아오는 화면 (/checkout/:orderId/complete)
 *
 * 모바일에서는 포트원 결제창이 "팝업"이 아니라 결제사 페이지로 "이동"해요.
 * 결제가 끝나면 Checkout.jsx 의 redirectUrl 로 돌아오면서, 주소 뒤에 결과를 붙여 줘요.
 *   성공: /checkout/12/complete?paymentId=pg-xxx&txId=...&transactionType=PAYMENT
 *   실패: /checkout/12/complete?paymentId=pg-xxx&code=FAILURE_TYPE_PG&message=사용자가 결제를 취소했습니다
 * (주소 뒤 이름은 SDK 의 PaymentResponse 타입과 같다고 보고 만들었어요. 확실하지 않음: 포트원 문서 확인 필요)
 *
 * 그래서 이 화면이 PC 결제의 ③(실패 확인)과 ④(서버 승인 요청)를 대신 해요.
 */
export default function PaymentComplete() {
  const { orderId } = useParams();
  const [params] = useSearchParams();
  const paymentId = params.get('paymentId'); // 우리 서버가 만든 pgId 가 그대로 돌아와요
  const code = params.get('code');           // 있으면 실패
  const failMessage = params.get('message');

  // 화면 상태: 'confirming'(승인 중) → 'done'(성공) 또는 'fail'(실패)
  const [phase, setPhase] = useState(code ? 'fail' : 'confirming');
  const [error, setError] = useState(code ? failMessage || '결제가 취소되었거나 실패했어요.' : '');

  // 개발 모드(React.StrictMode)에서는 useEffect 가 두 번 실행돼요.
  // 승인 요청이 두 번 가지 않게 "이미 보냈어요" 표시를 해 둬요.
  const sent = useRef(false);

  useEffect(() => {
    if (code || sent.current) return;
    sent.current = true;

    if (!paymentId) {
      setPhase('fail');
      setError('결제 정보를 찾을 수 없어요.');
      return;
    }

    paymentApi
      .confirm(paymentId, orderId)
      .then(() => setPhase('done'))
      .catch((e) => {
        setPhase('fail');
        setError(e.message);
      });
  }, [code, paymentId, orderId]);

  return (
    <div className="screen">
      <BackHeader title="결제 결과" to="/my" />
      <div className="screen-body pad stack gap-16" style={{ alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
        {phase === 'confirming' && (
          <>
            <Loading />
            <span className="muted">결제를 확인하고 있어요. 화면을 닫지 말아 주세요.</span>
          </>
        )}

        {phase === 'done' && (
          <>
            <span className="icon-circle" style={{ width: 72, height: 72, background: 'var(--green-soft)', color: '#145C32' }}>
              <Icon name="ticket" size={34} />
            </span>
            <span className="display" style={{ fontSize: 22 }}>결제가 완료됐어요</span>
            <span className="muted" style={{ fontSize: 14 }}>주문번호 {orderId} · 입장권이 발급됐어요.</span>
            <Link to="/my/tickets" className="btn btn-primary" style={{ width: '100%' }}>내 입장권 보기</Link>
          </>
        )}

        {phase === 'fail' && (
          <>
            <span className="display" style={{ fontSize: 22 }}>결제를 완료하지 못했어요</span>
            <span className="error-text" role="alert" style={{ justifyContent: 'center' }}>{error}</span>
            {/* 주문은 아직 결제 대기 상태라 결제 화면에서 다시 시도할 수 있어요 */}
            <Link to={`/checkout/${orderId}`} className="btn btn-primary" style={{ width: '100%' }}>다시 결제하기</Link>
            <Link to="/my" className="small muted">마이페이지 결제 내역 보기</Link>
          </>
        )}
      </div>
    </div>
  );
}
