import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';

/*
 * 회원 탈퇴 확인창 (아래에서 올라오는 시트)
 *
 * 탈퇴는 되돌릴 수 없어서 "안내를 확인했어요" 체크를 해야 버튼이 눌려요.
 * 안내 문구는 백엔드 UserWithdrawalService.java 가 실제로 하는 일과 맞춰 적었어요.
 *  - 미사용 입장권이 있으면 탈퇴 불가 (409)
 *  - 이용 예정 예약은 자동 취소
 *  - 탈퇴하면 상태가 WITHDRAWN 이 되어 로그인이 막혀요 (AuthService 가 ACTIVE 만 통과)
 */
export default function WithdrawSheet({ onClose, onDone }) {
  const { withdraw } = useAuth();
  const [agreed, setAgreed] = useState(false);
  const [error, setError] = useState(null); // ApiError 객체를 그대로 담아요 (status 를 보려고)
  const [working, setWorking] = useState(false);

  async function submit() {
    setError(null);
    setWorking(true);
    try {
      await withdraw(); // 성공하면 AuthContext 가 로그인 정보를 지워요
      onDone();
    } catch (e) {
      setError(e);
      setWorking(false); // 성공하면 화면이 바뀌니까, 실패했을 때만 버튼을 다시 살려요
    }
  }

  const close = () => { if (!working) onClose(); };

  // 409 = 미사용 입장권 때문에 막힘 → 입장권 화면으로 가는 길을 같이 보여 줘요
  const blockedByTicket = error?.status === 409;

  return (
    <div className="sheet-backdrop" onClick={close}>
      <div className="sheet" role="dialog" aria-label="회원 탈퇴" onClick={(e) => e.stopPropagation()}>
        <span className="display" style={{ fontSize: 21 }}>정말 탈퇴할까요?</span>

        <ul className="muted" style={{ fontSize: 13, lineHeight: 1.6, margin: 0, paddingLeft: 18 }}>
          <li>사용하지 않은 입장권이 있으면 탈퇴할 수 없어요. 먼저 환불해 주세요.</li>
          <li>앞으로 이용할 어트랙션 예약은 모두 자동으로 취소돼요.</li>
          <li>탈퇴한 계정으로는 다시 로그인할 수 없어요.</li>
        </ul>

        <label className="row gap-8" style={{ fontSize: 14, fontWeight: 500, cursor: 'pointer' }}>
          <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} disabled={working} />
          위 내용을 확인했어요.
        </label>

        {error && <span className="error-text" role="alert">{error.message}</span>}
        {blockedByTicket && (
          <Link to="/my/tickets" className="bold" style={{ fontSize: 13, color: 'var(--blue-ink)' }}>
            내 입장권 보러 가기
          </Link>
        )}

        <div className="row gap-10" style={{ marginTop: 6 }}>
          <button type="button" onClick={close} disabled={working} className="bold"
            style={{ flex: 1, height: 52, borderRadius: 16, border: '2px solid var(--line)', background: '#fff', fontSize: 15 }}>
            닫기
          </button>
          <button type="button" onClick={submit} disabled={!agreed || working} className="bold"
            style={{ flex: 1, height: 52, borderRadius: 16, border: 0, background: '#C2571A', color: '#fff', fontSize: 15,
              boxShadow: '0 4px 0 #8F3D0E', opacity: !agreed || working ? 0.5 : 1 }}>
            {working ? '처리 중...' : '탈퇴하기'}
          </button>
        </div>
      </div>
    </div>
  );
}
