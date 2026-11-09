// k6에서만 실행하는 로그인 도우미다. Node.js나 외부 패키지는 사용하지 않는다.
import http from 'k6/http';

export const BASE = 'http://app:8080';
const PASSWORD = 'PerfLocal123!';

export function csrfHeaders() {
  http.cookieJar().clear(BASE);
  const response = http.get(`${BASE}/api/auth/csrf`, {
    tags: { endpoint: 'auth-prepare' }, timeout: '10s',
  });
  if (response.status !== 200) throw new Error(`CSRF 조회 실패: HTTP ${response.status}`);
  const data = response.json().data;
  // Secure 쿠키는 로컬 HTTP에서 자동 전송되지 않아 이 전용 클라이언트에서만 직접 전달한다.
  const cookie = Object.keys(response.cookies).map(name =>
    `${name}=${response.cookies[name][0].value}`).join('; ');
  return { 'Content-Type': 'application/json', [data.headerName]: data.token, Cookie: cookie };
}

export function login(email, minimumValiditySeconds = 120) {
  const response = http.post(`${BASE}/api/auth/login`, JSON.stringify({
    email, password: PASSWORD,
  }), { headers: csrfHeaders(), tags: { endpoint: 'auth-prepare' }, timeout: '15s' });
  if (response.status !== 200) throw new Error(`테스트 계정 로그인 실패: HTTP ${response.status}`);
  const data = response.json().data;
  if (!data || !data.accessToken || data.tokenType !== 'Bearer'
      || !Number.isFinite(data.expiresIn) || data.expiresIn < minimumValiditySeconds) {
    throw new Error('Access Token 또는 유효 시간 확인 실패');
  }
  return data.accessToken;
}

export function ensureAccount(index) {
  const suffix = String(index).padStart(4, '0');
  const email = `kan134-${suffix}@example.test`;
  const response = http.post(`${BASE}/api/users/signup`, JSON.stringify({
    email, password: PASSWORD, nickname: `kan134-${suffix}`,
  }), { headers: csrfHeaders(), tags: { endpoint: 'account-prepare' }, timeout: '15s',
    responseCallback: http.expectedStatuses(201, 409) });
  // 기존 계정의 409도 같은 테스트 비밀번호로 로그인되는지 확인해야 재사용할 수 있다.
  if (response.status !== 201 && response.status !== 409) {
    throw new Error(`테스트 계정 준비 실패: HTTP ${response.status}`);
  }
  login(email);
}
