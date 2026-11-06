import { createContext, useContext, useEffect, useState } from 'react';
import { authApi } from '../api/index.js';
import { clearAccessToken, setAccessToken, tryRefresh } from '../api/http.js';

/*
 * "지금 누가 로그인했는지"를 앱 전체에서 함께 쓰는 저장소입니다.
 * 어느 화면에서든 const { user, login, logout } = useAuth(); 로 꺼내 쓸 수 있어요.
 *
 * 로그인 방식: 스프링부트의 JWT
 *  - 로그인 성공 → 서버가 Access Token(본문)과 Refresh Token(HttpOnly 쿠키)을 줘요.
 *  - Access Token 은 http.js 의 변수에 보관하고, 요청마다 Authorization 머리글로 보내요.
 *  - 새로고침하면 변수가 비니까, 앱이 켜질 때 refresh 로 다시 받아요.
 */
const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [checking, setChecking] = useState(true);

  // 앱이 켜질 때: Refresh Token 쿠키로 출입증을 다시 받아 보고, 되면 내 정보를 불러와요.
  useEffect(() => {
    (async () => {
      try {
        if (await tryRefresh()) setUser(await authApi.me());
        else setUser(null); // 쿠키가 없거나 만료 → 비로그인 상태
      } catch {
        setUser(null);
      } finally {
        setChecking(false);
      }
    })();
  }, []);

  async function login(email, password) {
    const token = await authApi.login(email, password); // { accessToken, tokenType, expiresIn }
    setAccessToken(token.accessToken);
    const me = await authApi.me();
    setUser(me);
    return me;
  }

  const value = {
    user,
    checking,
    isAdmin: user?.role === 'ADMIN',
    login,
    // 소셜 로그인: 일회용 코드를 출입증으로 바꾼 뒤, 일반 로그인과 똑같이 내 정보를 불러와요.
    async loginWithSocialCode(code) {
      const token = await authApi.exchangeSocialCode(code);
      setAccessToken(token.accessToken);
      const me = await authApi.me();
      setUser(me);
      return me;
    },
    async signup(nickname, email, password) {
      await authApi.signup(nickname, email, password); // 1) 가입
      return login(email, password);                   // 2) 바로 로그인
    },
    async logout() {
      try {
        await authApi.logout();
      } finally {
        clearAccessToken(); // 서버 요청이 실패해도 화면에서는 로그아웃 처리
        setUser(null);
      }
    },
    // 회원 탈퇴: 서버 탈퇴가 "성공했을 때만" 화면의 로그인 정보를 지워요.
    // (실패하면 에러가 그대로 화면으로 올라가고, 로그인 상태는 그대로예요)
    async withdraw() {
      await authApi.withdraw();
      clearAccessToken();
      setUser(null);
    },
    setUser,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}
