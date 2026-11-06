import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { FloatingBack } from '../components/ui.jsx';
import { useAuth } from '../context/AuthContext.jsx';
import { OAUTH_BASE } from '../config.js';

// 백엔드(application-oauth.yml)에 등록된 제공자만 버튼을 만들어요. (네이버는 백엔드에 없어서 뺐어요)
// id 는 백엔드 registrationId 와 같아야 해요 → /oauth2/authorization/{id}
const SOCIAL_PROVIDERS = [
  { id: 'kakao', label: '카카오로 시작', style: { background: '#FEE500', color: 'rgba(0,0,0,0.85)', border: 0 } },
  { id: 'google', label: 'Google로 시작', style: { background: '#fff', color: '#1F1F1F', border: '1.5px solid #DADCE0' } },
];

export default function Login() {
  const navigate = useNavigate();
  const location = useLocation(); // 어디서 왔는지(state.from) 읽으려고
  const { login, signup } = useAuth();

  const [tab, setTab] = useState('login'); // 'login' | 'signup'
  const [form, setForm] = useState({ nickname: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // 입력칸 값이 바뀔 때마다 form 상태를 갱신하는 함수
  const onChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  async function onSubmit(e) {
    e.preventDefault(); // 폼 제출 시 페이지가 새로고침되는 기본 동작 막기
    setError('');
    setSubmitting(true);
    try {
      if (tab === 'login') await login(form.email, form.password);
      else await signup(form.nickname, form.email, form.password);
      // 관리자 화면 등에서 쫓겨 온 경우(RequireAdmin) 그 주소로, 아니면 홈으로 돌아가요.
      // replace: 뒤로 가기를 눌러도 로그인 화면으로 다시 오지 않게 해요.
      navigate(location.state?.from ?? '/', { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="screen">
      <div className="hero" style={{ height: 190 }}>
        <img src="/images/park.jpg" alt="성, 롤러코스터, 대관람차가 보이는 테마파크 전경" style={{ objectPosition: 'center 40%' }} />
        <FloatingBack to="/" />
      </div>

      <form onSubmit={onSubmit} className="stack gap-16"
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', marginTop: -28, position: 'relative', background: '#fff', borderRadius: '28px 28px 0 0', padding: '26px 22px 20px', boxShadow: '0 -8px 24px rgba(31,42,68,0.08)' }}>
        <div className="stack gap-4">
          <h1 className="display" style={{ fontSize: 30 }}>반가워요!</h1>
          <p className="muted" style={{ margin: 0, fontSize: 14 }}>입장권 구매와 어트랙션 예약을 위해 로그인해 주세요.</p>
        </div>

        <div className="segment" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'login'} className={tab === 'login' ? 'on' : ''} onClick={() => { setTab('login'); setError(''); }}>로그인</button>
          <button type="button" role="tab" aria-selected={tab === 'signup'} className={tab === 'signup' ? 'on' : ''} onClick={() => { setTab('signup'); setError(''); }}>회원가입</button>
        </div>

        {tab === 'signup' && (
          <div className="field">
            <label htmlFor="nickname">닉네임</label>
            <input id="nickname" name="nickname" className="input" placeholder="파크에서 불릴 이름" value={form.nickname} onChange={onChange} required />
          </div>
        )}
        <div className="field">
          <label htmlFor="email">이메일</label>
          <input id="email" name="email" type="email" className="input" value={form.email} onChange={onChange} required autoComplete="email" />
        </div>
        <div className="field">
          <label htmlFor="password">비밀번호</label>
          <input id="password" name="password" type="password" className={`input${error ? ' error' : ''}`} value={form.password} onChange={onChange} required
            autoComplete={tab === 'login' ? 'current-password' : 'new-password'} />
          {error && <span className="error-text" role="alert"><Icon name="alert" size={14} strokeWidth={2} />{error}</span>}
        </div>

        <button type="submit" className="btn btn-primary" disabled={submitting} style={{ marginTop: 4 }}>
          {submitting ? '처리 중…' : tab === 'login' ? '로그인' : '가입하고 시작하기'}
        </button>

        {/* 소셜 로그인 */}
        <div className="row gap-10 small" style={{ color: 'var(--subtle)' }}>
          <span style={{ flex: 1, height: 1, background: '#DCE5F0' }} />
          소셜 계정으로 시작하기
          <span style={{ flex: 1, height: 1, background: '#DCE5F0' }} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 8 }}>
          {SOCIAL_PROVIDERS.map((p) => (
            // <Link> 가 아니라 <a> 를 써요: 리액트 안에서 화면만 바꾸는 게 아니라
            // 브라우저가 백엔드 → 카카오/구글 로그인 페이지로 "진짜 이동"해야 하기 때문이에요.
            <a key={p.id} href={`${OAUTH_BASE}/oauth2/authorization/${p.id}`} className="bold"
              style={{ height: 46, borderRadius: 14, display: 'flex', alignItems: 'center', justifyContent: 'center',
                textDecoration: 'none', fontSize: 14, ...p.style }}>
              {p.label}
            </a>
          ))}
        </div>
      </form>
    </div>
  );
}
