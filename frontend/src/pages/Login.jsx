import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Icon from '../components/Icon.jsx';
import { FloatingBack } from '../components/ui.jsx';
import { useAuth } from '../context/AuthContext.jsx';
import { USE_MOCK } from '../config.js';

export default function Login() {
  const navigate = useNavigate();
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
      navigate('/'); // 성공하면 홈으로
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

        {USE_MOCK && (
          <p className="small muted" style={{ margin: 0, textAlign: 'center' }}>
            테스트 계정: test@example.com / 1234 (관리자: admin@example.com / 1234)
          </p>
        )}

        <div className="row gap-10 small" style={{ color: 'var(--subtle)' }}>
          <span style={{ flex: 1, height: 1, background: '#DCE5F0' }} />
          소셜 로그인 (추후 제공)
          <span style={{ flex: 1, height: 1, background: '#DCE5F0' }} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8 }}>
          {['카카오', 'Google', '네이버'].map((n) => (
            <button key={n} type="button" disabled style={{ height: 46, borderRadius: 14, border: '1.5px dashed #C9D4E3', background: '#F7F9FC', color: 'var(--subtle)', fontSize: 13 }}>{n}</button>
          ))}
        </div>
      </form>
    </div>
  );
}
