import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { Badge, Empty, TabBar } from '../components/ui.jsx';
import { useApi } from '../hooks/useApi.js';
import { authApi, orderApi } from '../api/index.js';
import { MEMBER_ROLE, ORDER_STATUS, formatPrice } from '../api/labels.js';
import { useAuth } from '../context/AuthContext.jsx';
import PasswordChangeSheet from '../components/PasswordChangeSheet.jsx';
import WithdrawSheet from '../components/WithdrawSheet.jsx';
import OrderCancelSheet from '../components/OrderCancelSheet.jsx';


// 결제 내역: 처음에는 최근 몇 건만 보여 주고, 나머지는 "더 보기"로 펼쳐요.
const ORDER_PREVIEW = 3;
// 서버에서 받아 오는 최대 건수 (api/index.js 의 orderApi.mine() size 와 같은 값이어야 해요)
const ORDER_FETCH_MAX = 20;

// 가입 방식 → 화면에 보여 줄 이름
const SOCIAL_LABEL = { KAKAO: '카카오', GOOGLE: 'Google' };

export default function MyPage() {
  const navigate = useNavigate();
  const { user, setUser, logout, isAdmin } = useAuth();
  const orders = useApi(() => (user ? orderApi.mine() : Promise.resolve([])), [user]);

  const [nickname, setNickname] = useState('');
  const [message, setMessage] = useState('');
  // 지금 열려 있는 확인창: null(없음) | 'password'(비밀번호 변경) | 'withdraw'(회원 탈퇴)
  const [sheet, setSheet] = useState(null);
  const [cancelOrder, setCancelOrder] = useState(null); // 취소 확인창에 띄울 주문
  const [orderMessage, setOrderMessage] = useState(''); // 결제 내역 위에 보여 줄 안내
  const [showAllOrders, setShowAllOrders] = useState(false); // 결제 내역 "더 보기"를 눌렀는지
  useEffect(() => { if (user) setNickname(user.nickname); }, [user]);

  // 가입 방식(GET /api/users/me 의 provider): LOCAL | KAKAO | GOOGLE
  // provider 가 없을 때(백엔드가 아직 안 바뀐 경우)는 일반 회원처럼 버튼을 보여 줘요.
  const isSocial = Boolean(user?.provider) && user.provider !== 'LOCAL';

  async function saveNickname() {
    setMessage('');
    try {
      setUser(await authApi.updateNickname(nickname));
      setMessage('닉네임을 바꿨어요.');
    } catch (e) {
      setMessage(e.message);
    }
  }

  async function onLogout() {
    await logout();
    navigate('/login');
  }

  function onPasswordChanged() {
    setSheet(null);
    setMessage('비밀번호를 바꿨어요.');
  }

  function onWithdrawn() {
    setSheet(null);
    alert('회원 탈퇴가 완료되었어요. 그동안 이용해 주셔서 고마워요.');
    navigate('/', { replace: true }); // 뒤로가기로 마이페이지에 돌아오지 않게 replace
  }

  return (
    <div className="screen">
      <div className="screen-body">
        <div className="hero" style={{ height: 128, background: 'var(--blue)' }}>
          <h1 className="display pill-white" style={{ position: 'absolute', top: 14, left: 16, borderRadius: 14, padding: '4px 12px', fontSize: 20 }}>마이페이지</h1>
        </div>

        {!user ? (
          <Empty>로그인이 필요해요.<br /><Link to="/login">로그인하러 가기</Link></Empty>
        ) : (
          <>
            <div className="card row gap-14" style={{ margin: '-40px 16px 0', position: 'relative', borderRadius: 22, padding: '14px 16px', boxShadow: 'var(--shadow-strong)' }}>
              <span className="icon-circle" style={{ width: 64, height: 64, background: 'var(--yellow-soft)', border: '3px solid #fff', boxShadow: '0 0 0 2px var(--yellow)', color: 'var(--yellow-ink)' }}>
                <Icon name="user" size={30} strokeWidth={1.8} />
              </span>
              <span className="stack grow" style={{ gap: 2 }}>
                <span className="display" style={{ fontSize: 22 }}>{user.nickname}</span>
                <span className="muted" style={{ fontSize: 13 }}>{user.email}</span>
              </span>
              <Badge tone={isAdmin ? 'yellow' : 'gray'}>{MEMBER_ROLE[user.role]} 회원</Badge>
            </div>

            <div className="pad stack gap-14" style={{ paddingTop: 14 }}>
              <div className="card stack gap-10" style={{ borderRadius: 22, padding: 16 }}>
                <h2 className="display" style={{ fontSize: 18 }}>회원 정보 수정</h2>
                <label htmlFor="nickname" className="small muted">닉네임</label>
                <div className="row gap-8">
                  <input id="nickname" className="input" value={nickname} onChange={(e) => setNickname(e.target.value)} style={{ flex: 1, minWidth: 0, height: 48 }} />
                  <button type="button" className="btn btn-sm btn-dark" style={{ width: 72, height: 48, borderRadius: 14 }} onClick={saveNickname}>변경</button>
                </div>
                {message && <span className="small" style={{ color: 'var(--blue-ink)' }}>{message}</span>}
                {/* 소셜 회원(카카오·구글)은 우리 서버에 비밀번호가 없어서 변경 버튼 대신 안내를 보여 줘요 */}
                {isSocial ? (
                  <span className="small muted">{SOCIAL_LABEL[user.provider] ?? '소셜'} 계정으로 가입해서 비밀번호가 없어요.</span>
                ) : (
                  <button type="button" className="row between" onClick={() => { setMessage(''); setSheet('password'); }}
                    style={{ height: 48, borderRadius: 14, background: 'var(--soft)', border: 0, padding: '0 14px', fontSize: 14, fontWeight: 500 }}>
                    비밀번호 변경 <Icon name="chevron" size={18} strokeWidth={2} color="var(--subtle)" />
                  </button>
                )}
              </div>

              <div className="stack gap-8">
                <div className="row between">
                  <h2 className="display" style={{ fontSize: 18 }}>결제 내역</h2>
                  <Link to="/my/tickets" className="bold" style={{ fontSize: 13, textDecoration: 'none' }}>입장권 보기</Link>
                </div>
                {orderMessage && <div className="toast">{orderMessage}</div>}
                {orders.error && <span className="error-text" role="alert">{orders.error.message}</span>}
                {orders.data?.length === 0 && <span className="small muted">결제 내역이 없어요.</span>}
                {/* 펼치기 전에는 앞에서부터 ORDER_PREVIEW 건만 (목록은 최신순이라 최근 주문이 보여요) */}
                {(showAllOrders ? orders.data : orders.data?.slice(0, ORDER_PREVIEW))?.map((o) => (
                  <div key={o.id} className="card row between" style={{ borderRadius: 18, padding: '14px 16px' }}>
                    <span className="stack" style={{ gap: 2 }}>
                      <span className="bold" style={{ fontSize: 15 }}>주문번호 {o.id}</span>
                      {/* 상품 이름 × 수량 (예: 종일권 × 2) */}
                      {o.productSummary && <span style={{ fontSize: 14 }}>{o.productSummary}</span>}
                      <span className="small muted">{o.createdAt}</span>
                      {/* 결제 대기 주문은 결제 화면으로 다시 갈 수 있어요 */}
                      {/* 결제 대기 주문은 결제를 이어서 하거나, 취소할 수 있어요 */}
                      {o.status === 'PENDING' && (
                        <span className="row gap-12">
                          <Link to={`/checkout/${o.id}`} className="bold" style={{ fontSize: 13, color: 'var(--blue-ink)' }}>결제 이어서 하기</Link>
                          <button type="button" onClick={() => { setOrderMessage(''); setCancelOrder(o); }} className="bold"
                            style={{ border: 0, background: 'none', padding: 0, fontSize: 13, color: 'var(--orange-ink)' }}>
                            주문 취소
                          </button>
                        </span>
                      )}
                    </span>
                    <span className="stack" style={{ alignItems: 'flex-end', gap: 4 }}>
                      <span className="display" style={{ fontSize: 17 }}>{formatPrice(o.totalPrice)}</span>
                      <Badge status={ORDER_STATUS[o.status]} small />
                    </span>
                  </div>
                ))}
                {/* 더 보기 / 접기: 주문이 ORDER_PREVIEW 건보다 많을 때만 보여요 */}
                {orders.data?.length > ORDER_PREVIEW && (
                  <button type="button" className="btn btn-sm btn-outline" style={{ width: '100%' }}
                    onClick={() => setShowAllOrders((v) => !v)}>
                    {showAllOrders ? '접기' : `더 보기 (${orders.data.length - ORDER_PREVIEW}건)`}
                  </button>
                )}
                {/* 다 펼쳤는데 딱 최대 건수만큼 왔으면, 더 오래된 주문이 있을 수 있다고 알려 줘요 */}
                {showAllOrders && orders.data?.length === ORDER_FETCH_MAX && (
                  <span className="small muted" style={{ textAlign: 'center' }}>최근 결제 {ORDER_FETCH_MAX}건까지 보여요.</span>
                )}
              </div>

              {isAdmin && <Link to="/admin" className="btn btn-outline" style={{ height: 48 }}>관리자 화면으로</Link>}

              <div className="row between" style={{ padding: '6px 4px 0' }}>
                <button type="button" onClick={onLogout} style={{ border: 0, background: 'none', fontSize: 13, textDecoration: 'underline', padding: 0 }}>로그아웃</button>
                <button type="button" onClick={() => setSheet('withdraw')} style={{ border: 0, background: 'none', fontSize: 13, color: 'var(--muted)', textDecoration: 'underline', padding: 0 }}>회원 탈퇴</button>
              </div>
            </div>
          </>
        )}
      </div>

      {user && sheet === 'password' && (
        <PasswordChangeSheet onClose={() => setSheet(null)} onDone={onPasswordChanged} />
      )}
      {user && cancelOrder && (
        <OrderCancelSheet
          order={cancelOrder}
          onClose={() => setCancelOrder(null)}
          onDone={() => { setCancelOrder(null); setOrderMessage('주문을 취소했어요.'); orders.reload(); }}
        />
      )}
      {user && sheet === 'withdraw' && (
        <WithdrawSheet onClose={() => setSheet(null)} onDone={onWithdrawn} />
      )}
      <TabBar />
    </div>
  );
}
