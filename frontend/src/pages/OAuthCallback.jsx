import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Loading } from '../components/ui.jsx';
import { useAuth } from '../context/AuthContext.jsx';

/*
 * 소셜 로그인 도착 화면 (/oauth/callback)
 *
 * 전체 흐름
 *  1) 로그인 화면에서 [카카오로 시작] 클릭 → 백엔드 /oauth2/authorization/kakao 로 이동
 *  2) 카카오 로그인 → 백엔드가 회원 확인/가입 처리
 *  3) 백엔드가 이 화면으로 돌려보내 줘요.
 *       성공: /oauth/callback?code=일회용코드
 *       실패: /oauth/callback?error=실패코드
 *  4) 성공이면 일회용 코드를 /api/auth/oauth2/exchange 로 보내서 출입증(Access Token)으로 바꿔요.
 */

// 백엔드 SocialLoginError.java 의 실패 코드 → 화면에 보여 줄 문장
const ERROR_MESSAGES = {
  UNSUPPORTED_PROVIDER: '지원하지 않는 로그인 방식이에요.',
  INVALID_PROVIDER_USER_ID: '소셜 계정 정보를 확인하지 못했어요.',
  EMAIL_REQUIRED: '이메일 제공에 동의해야 가입할 수 있어요.',
  EMAIL_NOT_VERIFIED: '인증된 이메일이 있는 계정만 가입할 수 있어요.',
  EMAIL_ALREADY_EXISTS: '이미 같은 이메일로 가입된 계정이 있어요. 이메일로 로그인해 주세요.',
  USER_NOT_ACTIVE: '이용할 수 없는 계정이에요. (정지 또는 탈퇴한 계정)',
  INVALID_USER_INFO: '소셜 계정 정보가 가입 조건에 맞지 않아요.',
  AUTHENTICATION_FAILED: '소셜 로그인에 실패했어요. 다시 시도해 주세요.',
};

export default function OAuthCallback() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { checking, loginWithSocialCode } = useAuth();
  const [message, setMessage] = useState('');

  // 일회용 코드는 "딱 한 번"만 쓸 수 있어요.
  // 개발 모드의 React.StrictMode 는 useEffect 를 두 번 실행하기 때문에,
  // "이미 보냈는지" 표시해 두고 두 번째는 건너뛰어요. (useRef 값은 다시 그려도 유지돼요)
  const sent = useRef(false);

  useEffect(() => {
    // 앱이 켜질 때 AuthContext 가 하는 "자동 로그인 확인(refresh)"이 끝날 때까지 기다려요.
    // 동시에 하면, 늦게 끝난 refresh 실패가 방금 받은 출입증을 지워 버릴 수 있어요.
    if (checking || sent.current) return;
    sent.current = true;

    const code = params.get('code');
    const error = params.get('error');

    if (error || !code) {
      setMessage(ERROR_MESSAGES[error] || ERROR_MESSAGES.AUTHENTICATION_FAILED);
      return;
    }

    loginWithSocialCode(code)
      // replace: 주소창의 ?code=... 를 기록에서 지워요. (뒤로 가기로 다시 오지 않게)
      .then(() => navigate('/', { replace: true }))
      .catch((e) => setMessage(e.message)); // 예) 코드가 만료됐거나 이미 사용됨
  }, [checking, params, loginWithSocialCode, navigate]);

  return (
    <div className="screen">
      <div className="screen-body pad stack gap-14" style={{ justifyContent: 'center', alignItems: 'center', textAlign: 'center' }}>
        {!message ? (
          <Loading />
        ) : (
          <>
            <span className="display" style={{ fontSize: 21 }}>로그인하지 못했어요</span>
            <span className="error-text" role="alert" style={{ justifyContent: 'center' }}>{message}</span>
            <Link to="/login" replace className="btn btn-primary" style={{ width: '100%' }}>로그인 화면으로</Link>
          </>
        )}
      </div>
    </div>
  );
}
