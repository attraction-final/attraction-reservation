import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const users = new SharedArray('재발급 전용 회원', () =>
    JSON.parse(open('./.local/users.json'))
);

const BASE = 'http://host.docker.internal:18090';
const PASSWORD = 'PerfLocal123!';
const MODE = __ENV.MODE || 'smoke';
const VUS = Number(__ENV.VUS || 1);

if (!['smoke', 'load'].includes(MODE)) {
    throw new Error('MODE는 smoke 또는 load여야 합니다.');
}

if (!Number.isInteger(VUS) || VUS < 1 || VUS > users.length) {
    throw new Error('VUS는 준비된 회원 수 이내의 양의 정수여야 합니다.');
}

const attempts = new Counter('auth_refresh_attempts');
const successes = new Counter('auth_refresh_successes');
const failed = new Rate('auth_refresh_failed');
const duration = new Trend('auth_refresh_duration', true);

const thresholds = {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    auth_refresh_failed: ['rate==0'],
    auth_refresh_attempts: ['count>0'],
    auth_refresh_successes: ['count>0'],
};

if (MODE === 'smoke') {
    thresholds.auth_refresh_attempts = [`count==${VUS * 5}`];
    thresholds.auth_refresh_successes = [`count==${VUS * 5}`];
}

export const options = {
    scenarios: {
        refresh: MODE === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus: VUS,
                iterations: 5,
                maxDuration: '30s',
            }
            : {
                executor: 'constant-vus',
                vus: VUS,
                duration: '60s',
                gracefulStop: '10s',
            },
    },
    thresholds,
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)',
    ],
};

// 각 가상 사용자의 인증 정보는 실행 중 메모리에만 보관한다.
let refreshHeaders;

function jsonOrEmpty(response) {
    try {
        return response.json();
    } catch (_) {
        return {};
    }
}

function claimsOrEmpty(token) {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) {
            return {};
        }
        return JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's'));
    } catch (_) {
        return {};
    }
}

function prepareSession(user) {
    const csrfResponse = http.get(`${BASE}/api/auth/csrf`, {
        tags: { name: '재발급 준비 — CSRF' },
        timeout: '10s',
        redirects: 0,
    });

    const csrfBody = jsonOrEmpty(csrfResponse);
    const csrfData = csrfBody.data || {};
    const cookies = [];

    for (const name of Object.keys(csrfResponse.cookies || {})) {
        for (const cookie of csrfResponse.cookies[name]) {
            cookies.push(`${name}=${cookie.value}`);
        }
    }

    const csrfValid = check(csrfResponse, {
        '재발급 준비 CSRF 성공': () =>
            csrfResponse.status === 200 &&
            csrfBody.success === true &&
            typeof csrfData.headerName === 'string' &&
            typeof csrfData.token === 'string' &&
            cookies.length > 0,
    });

    if (!csrfValid) {
        fail('CSRF 준비 실패. 응답 원문은 출력하지 않습니다.');
    }

    const headers = {
        'Content-Type': 'application/json',
        [csrfData.headerName]: csrfData.token,
        Cookie: cookies.join('; '),
    };

    const loginResponse = http.post(
        `${BASE}/api/auth/login`,
        JSON.stringify({
            email: user.email,
            password: PASSWORD,
        }),
        {
            headers,
            tags: { name: '재발급 준비 — 로그인' },
            timeout: '10s',
            redirects: 0,
        }
    );

    const loginBody = jsonOrEmpty(loginResponse);
    const refreshCookies = loginResponse.cookies.refreshToken || [];

    const loginValid = check(loginResponse, {
        '재발급 준비 로그인 성공': () =>
            loginResponse.status === 200 &&
            loginBody.success === true &&
            refreshCookies.length === 1 &&
            typeof refreshCookies[0].value === 'string' &&
            refreshCookies[0].value.length > 0,
    });

    if (!loginValid) {
        fail('로그인 준비 실패. 인증 정보는 출력하지 않습니다.');
    }

    // 로컬 HTTP 검사에서 Secure 쿠키를 명시적으로 전달한다.
    // 서버의 쿠키 보안 설정은 변경하지 않는다.
    return {
        ...headers,
        Cookie: headers.Cookie +
            `; refreshToken=${refreshCookies[0].value}`,
    };
}

export default function () {
    const user = users[__VU - 1];

    if (!refreshHeaders) {
        refreshHeaders = prepareSession(user);
    }

    attempts.add(1);

    const response = http.post(
        `${BASE}/api/auth/refresh`,
        null,
        {
            headers: refreshHeaders,
            tags: { name: '토큰 재발급' },
            timeout: '10s',
            redirects: 0,
        }
    );

    duration.add(response.timings.duration);

    const body = jsonOrEmpty(response);
    const data = body.data || {};
    const token = data.accessToken;
    const claims = claimsOrEmpty(
        typeof token === 'string' ? token : ''
    );

    // JWT 본문 해석은 서명 검증과 다르다.
    // 실제 사용 가능 여부는 앞선 API 정확성 검사에서 확인했다.
    const valid = check(response, {
        '재발급 HTTP 200': () => response.status === 200,
        '재발급 업무 성공': () => body.success === true,
        '접근 토큰 반환': () =>
            typeof token === 'string' && token.length > 0,
        '응답 토큰 종류·유효 시간': () =>
            data.tokenType === 'Bearer' && data.expiresIn === 600,
        '토큰 회원·종류 일치': () =>
            String(claims.sub) === String(user.id) &&
            claims.tokenType === 'ACCESS',
        '토큰 수명 600초': () => claims.exp - claims.iat === 600,
        '갱신 토큰 재발급 없음': () =>
            !Object.prototype.hasOwnProperty.call(data, 'refreshToken') &&
            (response.cookies.refreshToken || []).length === 0,
    });

    failed.add(!valid);

    if (valid) {
        successes.add(1);
    }

    sleep(1);
}

export function handleSummary(data) {
    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        stdout: JSON.stringify({
            mode: MODE,
            vus: VUS,
            attempts: data.metrics.auth_refresh_attempts,
            successes: data.metrics.auth_refresh_successes,
            latency: data.metrics.auth_refresh_duration,
            failed: data.metrics.auth_refresh_failed,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}