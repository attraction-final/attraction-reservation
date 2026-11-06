import { useState } from 'react';
import { authApi } from '../api/index.js';

/*
 * 비밀번호 변경 확인창 (아래에서 올라오는 시트)
 *
 * 1) 현재 비밀번호 / 새 비밀번호 / 새 비밀번호 확인 을 입력받아요.
 * 2) 서버에 보내기 전에 프론트에서 먼저 간단히 검사해요. (빨리 알려 주려고)
 * 3) 진짜 검사는 서버(UpdatePasswordRequest.java)가 다시 해요. 서버 메시지가 오면 그대로 보여 줘요.
 */

// 백엔드 UpdatePasswordRequest 의 @Pattern 과 같은 규칙
// 8~20자, 공백 없음, 영문·숫자·특수문자 각각 1개 이상
const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s])\S{8,20}$/;

const EMPTY_FORM = { currentPassword: '', newPassword: '', newPasswordConfirm: '' };

// 서버에 보내기 전 검사. 문제가 있으면 안내 문장을, 없으면 null 을 돌려줘요.
function validate(form) {
  if (!form.currentPassword) return '현재 비밀번호를 입력해 주세요.';
  if (!PASSWORD_RULE.test(form.newPassword)) {
    return '새 비밀번호는 8~20자이며 영문, 숫자, 특수문자를 각각 1개 이상 넣어 주세요.';
  }
  if (form.newPassword !== form.newPasswordConfirm) return '새 비밀번호가 서로 달라요.';
  if (form.currentPassword === form.newPassword) return '현재 비밀번호와 다른 비밀번호를 입력해 주세요.';
  return null;
}

export default function PasswordChangeSheet({ onClose, onDone }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  // 입력칸 3개를 함수 하나로 처리해요. (name 속성으로 어느 칸인지 구분)
  function onChange(e) {
    setForm({ ...form, [e.target.name]: e.target.value });
  }

  async function submit() {
    const problem = validate(form);
    if (problem) {
      setError(problem);
      return;
    }
    setError('');
    setSaving(true);
    try {
      await authApi.changePassword(form.currentPassword, form.newPassword);
      onDone(); // 부모(MyPage)에게 "성공했어요" 알리기 → 시트 닫고 안내 문구 보여 줌
    } catch (e) {
      setError(e.message); // 예) "현재 비밀번호가 일치하지 않습니다."
    } finally {
      setSaving(false);
    }
  }

  // 저장 중에는 바깥을 눌러도 닫히지 않게 해요.
  const close = () => { if (!saving) onClose(); };

  return (
    <div className="sheet-backdrop" onClick={close}>
      <div className="sheet" role="dialog" aria-label="비밀번호 변경" onClick={(e) => e.stopPropagation()}>
        <span className="display" style={{ fontSize: 21 }}>비밀번호 변경</span>
        <span className="muted" style={{ fontSize: 13, lineHeight: 1.55 }}>
          8~20자, 영문·숫자·특수문자를 각각 1개 이상 넣어 주세요.
        </span>

        <PasswordField id="currentPassword" label="현재 비밀번호" value={form.currentPassword}
          onChange={onChange} autoComplete="current-password" hasError={Boolean(error)} />
        <PasswordField id="newPassword" label="새 비밀번호" value={form.newPassword}
          onChange={onChange} autoComplete="new-password" hasError={Boolean(error)} />
        <PasswordField id="newPasswordConfirm" label="새 비밀번호 확인" value={form.newPasswordConfirm}
          onChange={onChange} autoComplete="new-password" hasError={Boolean(error)} />

        {error && <span className="error-text" role="alert">{error}</span>}

        <div className="row gap-10" style={{ marginTop: 6 }}>
          <button type="button" onClick={close} disabled={saving} className="bold"
            style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>
            닫기
          </button>
          <button type="button" onClick={submit} disabled={saving} className="btn btn-dark"
            style={{ flex: 1, height: 52, borderRadius: 16, fontSize: 15, opacity: saving ? 0.6 : 1 }}>
            {saving ? '변경 중...' : '변경하기'}
          </button>
        </div>
      </div>
    </div>
  );
}

// 비밀번호 입력칸 하나 (라벨 + 입력칸). 같은 모양이 3번 나와서 따로 뺐어요.
function PasswordField({ id, label, value, onChange, autoComplete, hasError }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} name={id} type="password" className={`input${hasError ? ' error' : ''}`}
        value={value} onChange={onChange} autoComplete={autoComplete} />
    </div>
  );
}
