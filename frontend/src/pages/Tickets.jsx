import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { BackHeader, ErrorBox, Loading } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { orderApi, parkApi, ticketApi } from '../api/index.js';
import { TICKET_PRODUCT_STATUS, dayOfWeek, formatPrice, shortDate } from '../api/labels.js';
import { useAuth } from '../context/AuthContext.jsx';

// 지금 살 수 있는 상품인지: 판매 중(ON_SALE)일 때만 true
const isOnSale = (p) => p?.status === 'ON_SALE';

export default function Tickets() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const park = useApi(() => parkApi.today(), []);
  const dates = useApi(() => parkApi.saleDates(), []);
  const products = useApi(() => ticketApi.products(), []);

  const [date, setDate] = useState(null);
  const [productId, setProductId] = useState(null);
  const [qty, setQty] = useState(1);
  const [error, setError] = useState('');

  // 처음 들어오면 "오늘"과 "살 수 있는 첫 번째 상품"을 기본 선택
  useEffect(() => { if (park.data && !date) setDate(park.data.date); }, [park.data, date]);
  useEffect(() => {
    if (!products.data?.length || productId) return;
    const firstOnSale = products.data.find(isOnSale);
    if (firstOnSale) setProductId(firstOnSale.id);
  }, [products.data, productId]);

  if (products.loading || dates.loading) return <div className="screen"><BackHeader title="입장권 구매" to="/" /><Loading /></div>;
  if (products.error) return <div className="screen"><BackHeader title="입장권 구매" to="/" /><ErrorBox error={products.error} onRetry={products.reload} /></div>;

  const product = products.data.find((p) => p.id === productId);
  const total = product?.price != null ? product.price * qty : null;
  const canOrder = Boolean(date && product && isOnSale(product));

  async function order() {
    if (!user) return navigate('/login');
    setError('');
    try {
      const o = await orderApi.create(date, productId, qty); // 주문서(결제 대기) 만들기
      // 주문 상세 응답에는 pgId 와 방문일이 없어서, 결제 화면으로 갈 때 "가방(state)"에 넣어 같이 보내요.
      navigate(`/checkout/${o.id}`, { state: { pgId: o.pgId, date } });
    } catch (e) {
      setError(e.message);
    }
  }

  return (
    <div className="screen">
      <BackHeader title="입장권 구매" to="/" />
      <div className="screen-body pad stack gap-16">
        {/* 운영 안내 배너: 고른 날짜·입장권에 따라 문구가 바뀌어요 */}
        <div className="row gap-14" style={{ flexShrink: 0, background: 'var(--blue)', borderRadius: 22, padding: '14px 16px', color: '#fff', boxShadow: '0 4px 0 var(--blue-dark)' }}>
          <span className="icon-circle" style={{ width: 46, height: 46, background: 'rgba(255,255,255,0.18)' }}><Icon name="clock" size={24} /></span>
          <span className="stack" style={{ gap: 2 }}>
            <span className="small" style={{ color: '#DCE8FF' }}>{date ? `${shortDate(date)} (${dayOfWeek(date)})` : ''} 운영 안내</span>
            <span className="display" style={{ fontSize: 22, lineHeight: 1.2 }}>{park.data ? `${park.data.openTime} – ${park.data.closeTime}` : '–'}</span>
            <span className="small">{product?.entryFrom ? `${product.name}은 ${product.entryFrom}부터 입장할 수 있어요` : `${product?.name ?? '입장권'}은 개장부터 폐장까지 입장할 수 있어요`}</span>
          </span>
        </div>

        {/* 날짜 고르기 */}
        <div className="card stack gap-12" style={{ borderRadius: 22, padding: 16 }}>
          <div className="row between" style={{ alignItems: 'baseline' }}>
            <h2 className="display" style={{ fontSize: 18 }}>이용 날짜</h2>
            {date && <span className="small muted">{date.slice(0, 4)}년 {Number(date.slice(5, 7))}월</span>}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))', gap: 6 }}>
            {(dates.data || []).map((d) => {
              const on = d.date === date;
              const style = !d.available
                ? { background: '#F1F3F7', border: '1.5px solid #F1F3F7', color: 'var(--subtle)' }
                : on ? { background: 'var(--blue)', border: '1.5px solid var(--blue)', color: '#fff', boxShadow: '0 3px 0 var(--blue-dark)' }
                : { background: '#fff', border: '1.5px solid var(--line)' };
              return (
                <button key={d.date} type="button" disabled={!d.available} aria-pressed={on} onClick={() => setDate(d.date)}
                  style={{ height: 58, borderRadius: 14, padding: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1, ...style }}>
                  <span style={{ fontSize: 11 }}>{dayOfWeek(d.date)}</span>
                  <span className="display" style={{ fontSize: 18, textDecoration: d.available ? 'none' : 'line-through' }}>{Number(d.date.slice(8))}</span>
                </button>
              );
            })}
          </div>
          <span className="small muted">취소선 날짜는 판매 중지 / 이용 불가</span>
        </div>

        {/* 입장권 종류 */}
        <div className="stack gap-10">
          <h2 className="display" style={{ fontSize: 18 }}>입장권 종류</h2>
          {products.data.map((p) => {
            const on = p.id === productId;
            const sale = isOnSale(p);
            // 판매 중이 아니면: 회색 배경 + 누를 수 없게
            const look = !sale
              ? { background: '#F1F3F7', border: '2px solid #F1F3F7', color: 'var(--subtle)', cursor: 'not-allowed' }
              : on ? { background: '#F2F7FF', border: '2px solid var(--blue)', boxShadow: '0 4px 14px rgba(47,111,219,0.12)' }
              : { background: '#fff', border: '2px solid #fff', boxShadow: 'var(--shadow)' };
            return (
              <button key={p.id} type="button" role="radio" aria-checked={on} disabled={!sale} onClick={() => setProductId(p.id)} className="row gap-12"
                style={{ width: '100%', padding: 16, borderRadius: 20, textAlign: 'left', ...look }}>
                <span className="icon-circle" style={{ width: 24, height: 24, border: `2px solid ${on ? 'var(--blue)' : '#AEB8CB'}` }}>
                  <span style={{ width: 12, height: 12, borderRadius: '50%', background: on ? 'var(--blue)' : 'transparent' }} />
                </span>
                <span className="stack grow" style={{ gap: 2 }}>
                  <span className="row gap-8" style={{ alignItems: 'center' }}>
                    <span className="bold" style={{ fontSize: 16 }}>{p.name}</span>
                    {/* 판매 중이 아니면 이름 옆에 "품절"·"판매 중지" 같은 이유를 작게 보여 줘요 */}
                    {!sale && <span className="small bold">{TICKET_PRODUCT_STATUS[p.status]?.label ?? '구매 불가'}</span>}
                  </span>
                  <span className="small muted">{p.hours}</span>
                </span>
                <span className="display" style={{ fontSize: 17, textDecoration: sale ? 'none' : 'line-through' }}>{formatPrice(p.price)}</span>
              </button>
            );
          })}
        </div>

        {/* 수량 */}
        <div className="card row between" style={{ borderRadius: 22, padding: '12px 16px' }}>
          <span className="display" style={{ fontSize: 18 }}>수량</span>
          <div className="row gap-4" style={{ background: 'var(--soft)', borderRadius: 999, padding: 4 }}>
            <button type="button" aria-label="수량 줄이기" onClick={() => setQty(Math.max(1, qty - 1))} style={{ width: 44, height: 44, borderRadius: '50%', border: 0, background: '#fff', fontSize: 20 }}>−</button>
            <span className="display" style={{ width: 36, textAlign: 'center', fontSize: 20 }}>{qty}</span>
            <button type="button" aria-label="수량 늘리기" onClick={() => setQty(Math.min(10, qty + 1))} style={{ width: 44, height: 44, borderRadius: '50%', border: 0, background: 'var(--blue)', color: '#fff', fontSize: 20 }}>+</button>
          </div>
        </div>
        {error && <span className="error-text" role="alert">{error}</span>}
      </div>

      <div className="bottom-bar">
        <div className="row between">
          <span className="muted" style={{ fontSize: 14 }}>{date ? `${shortDate(date)} · ` : ''}{product?.name} × {qty}</span>
          <span className="display" style={{ fontSize: 20 }}>{formatPrice(total)}</span>
        </div>
        <button type="button" className="btn btn-primary" onClick={order} disabled={!canOrder}>
          {!product ? '구매할 수 있는 입장권이 없어요' : user ? '주문하기' : '로그인하고 주문하기'}
        </button>
      </div>
    </div>
  );
}
