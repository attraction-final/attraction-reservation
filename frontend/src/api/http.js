import { API_BASE } from '../config.js';
import { ApiError } from './ApiError.js';

/**
 * 모든 API 요청이 지나가는 "단 하나의 문"입니다.
 *
 * 우리 스프링부트 서버의 약속 3가지를 여기서 한꺼번에 처리해요.
 *  1) 응답 포장지 벗기기 : 서버는 { success, message, data } 로 감싸서 보내요 → data 만 꺼내요.
 *  2) 출입증(Access Token) : 로그인하면 받은 토큰을 "Authorization: Bearer 토큰" 머리글에 붙여요.
 *  3) CSRF 도장       : /api/auth/** 와 /api/users/signup 에 POST 할 때는
 *                        먼저 /api/auth/csrf 에서 도장(토큰)을 받아 머리글에 같이 보내요.
 *
 * 그리고 401(출입증 만료)이 오면 /api/auth/refresh 로 새 출입증을 한 번 받아서 다시 시도해요.
 */

// ───────── 출입증(Access Token) 보관함 ─────────
// 메모리(변수)에만 둡니다. 새로고침하면 사라지지만,
// Refresh Token 쿠키가 있으니 앱이 켜질 때 refresh 로 다시 받아요. (AuthContext.jsx 참고)
let accessToken = null;
export function setAccessToken(token) { accessToken = token; }
export function clearAccessToken() { accessToken = null; }

// ───────── CSRF 도장이 필요한 주소인지 판단 ─────────
// SecurityConfig.java 의 csrfProtectedRequests 와 같은 규칙이에요.
function needsCsrf(method, path) {
  if (method === 'GET') return false;
  return path.startsWith('/auth/') || path === '/users/signup';
}

// CSRF 도장 받기: GET /api/auth/csrf → { headerName, token }
async function fetchCsrf() {
  const res = await fetch(API_BASE + '/auth/csrf', { credentials: 'include' });
  const json = await res.json().catch(() => null);
  if (!res.ok || !json?.data) throw new ApiError('CSRF 토큰을 받지 못했어요.', res.status);
  return json.data; // { headerName: 'X-XSRF-TOKEN', token: '...' }
}

// ───────── 실제로 서버에 한 번 요청하기 ─────────
async function send(method, path, body) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (needsCsrf(method, path)) {
    const csrf = await fetchCsrf();
    headers[csrf.headerName] = csrf.token;
  }

  const res = await fetch(API_BASE + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    credentials: 'include', // 쿠키(refreshToken, XSRF-TOKEN)를 같이 보내기
  });

  if (res.status === 204) return { res, json: null };
  const json = await res.json().catch(() => null);
  return { res, json };
}

// ───────── 출입증 재발급 (동시에 여러 번 부르지 않도록 하나로 묶어요) ─────────
let refreshing = null;
export function tryRefresh() {
  if (!refreshing) {
    refreshing = (async () => {
      const { res, json } = await send('POST', '/auth/refresh');
      if (!res.ok || !json?.data?.accessToken) {
        clearAccessToken();
        return false;
      }
      setAccessToken(json.data.accessToken);
      return true;
    })()
        .catch(() => { clearAccessToken(); return false; })
        .finally(() => { refreshing = null; });
  }
  return refreshing;
}

// 서버 에러 응답 → 화면에 보여 줄 문장 만들기
// 입력값 검증 실패면 data 에 { 필드명: '이유' } 가 들어 있어서 첫 번째 이유를 보여 줘요.
function errorMessage(json, status) {
  const fieldErrors = json?.data && typeof json.data === 'object' ? Object.values(json.data) : [];
  if (fieldErrors.length > 0 && typeof fieldErrors[0] === 'string') return fieldErrors[0];
  return json?.message || `요청에 실패했어요 (${status})`;
}

export async function request(method, path, body) {
  let { res, json } = await send(method, path, body);

  // 401 = 출입증이 없거나 만료됨 → 재발급 후 딱 한 번만 다시 시도
  const isAuthPath = path.startsWith('/auth/');
  if (res.status === 401 && !isAuthPath) {
    const ok = await tryRefresh();
    if (ok) ({ res, json } = await send(method, path, body));
  }

  if (!res.ok) throw new ApiError(errorMessage(json, res.status), res.status);
  return json?.data ?? null; // 포장지 벗기고 알맹이(data)만 돌려줘요
}

// 진짜 스프링부트 서버로 가는 요청
export const http = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body),
  put: (path, body) => request('PUT', path, body),
  patch: (path, body) => request('PATCH', path, body),
  delete: (path) => request('DELETE', path),
};

// { date: '2026-10-24', q: '' } → "?date=2026-10-24" (빈 값은 빼고 만들어 줘요)
export function toQuery(params = {}) {
  const sp = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') sp.append(k, v);
  });
  const s = sp.toString();
  return s ? `?${s}` : '';
}