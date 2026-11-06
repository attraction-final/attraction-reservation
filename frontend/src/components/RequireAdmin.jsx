import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { Empty, Loading } from './ui.jsx';
import { useAuth } from '../context/AuthContext.jsx';

/*
 * 관리자 화면 문지기
 *
 * App.jsx 에서 관리자 주소들을 이 컴포넌트로 감싸요.
 *   <Route element={<RequireAdmin />}>
 *     <Route path="/admin/slots" ... />
 *   </Route>
 *
 * 그러면 /admin/... 주소로 들어올 때마다 이 컴포넌트가 먼저 검사해요.
 *  1) 아직 로그인 확인 중   → "불러오는 중" 표시 (확인이 끝날 때까지 기다려요)
 *  2) 로그인 안 함          → 로그인 화면으로 보내요 (로그인 후 원래 주소로 돌아오게 기억)
 *  3) 로그인했지만 일반 회원 → "권한이 없어요" 안내
 *  4) 관리자               → <Outlet /> 자리에 원래 관리자 화면을 보여 줘요
 *
 * ⚠️ 이건 "보기 좋게" 막는 것이에요. 진짜 보안은 백엔드가 해요.
 *    (SecurityConfig 의 /api/admin/** → hasRole(ADMIN))
 *    프론트 코드는 사용자 브라우저에 있어서 마음먹으면 고칠 수 있기 때문이에요.
 */
export default function RequireAdmin() {
  const { user, checking, isAdmin } = useAuth();
  const location = useLocation();

  // 1) 새로고침 직후에는 "자동 로그인 확인(refresh)"이 끝나기 전이라 user 가 잠깐 null 이에요.
  //    이때 바로 로그인 화면으로 보내면, 관리자도 새로고침할 때마다 쫓겨나요. 그래서 기다려요.
  if (checking) {
    return <div className="screen"><Loading /></div>;
  }

  // 2) 로그인 안 함 → 로그인 화면으로. 지금 주소를 state 에 담아 두면 로그인 후 여기로 돌아와요.
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  // 3) 일반 회원
  if (!isAdmin) {
    return (
      <div className="screen">
        <div className="screen-body pad" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Empty>
            관리자만 볼 수 있는 화면이에요.<br />
            <Link to="/" replace>홈으로 가기</Link>
          </Empty>
        </div>
      </div>
    );
  }

  // 4) 관리자 → 원래 화면
  return <Outlet />;
}
