import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const users = new SharedArray('로그인 전용 회원', () =>
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

const attempts = new Counter('auth_login_attempts');
const successes = new Counter('auth_login_successes');
const failed = new Rate('auth_login_failed');
const duration = new Trend('auth_login_duration', true);

const thresholds = {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    auth_login_failed: ['rate==0'],
};

if (MODE === 'smoke') {
    thresholds.auth_login_attempts = [`count==${VUS * 5}`];
    thresholds.auth_login_successes = [`count==${VUS * 5}`];
}

export const options = {
    scenarios: {
        login: MODE === 'smoke'
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

// 각 가상 사용자는 자신의 CSRF 정보를 메모리에 보관합니다.
let loginHeaders;

function jsonOrEmpty(response) {
    try {
        return response.json();
    } catch (_) {
        return {};
    }
}

function prepareCsrf() {
    const response = http.get(`${BASE}/api/auth/csrf`, {
        tags: { name: '로그인 CSRF 준비' },
        timeout: '10s',
        redirects: 0,
    });

    const body = jsonOrEmpty(response);
    const data = body.data || {};
    const cookies = [];

    for (const name of Object.keys(response.cookies || {})) {
        for (const cookie of response.cookies[name]) {
            cookies.push(`${name}=${cookie.value}`);
        }
    }

    const valid = check(response, {
        'CSRF 준비 성공': () =>
            response.status === 200 &&
            body.success === true &&
            typeof data.headerName === 'string' &&
            typeof data.token === 'string' &&
            cookies.length > 0,
    });

    if (!valid) {
        fail('CSRF 준비 실패. 응답 원문은 출력하지 않습니다.');
    }

    // 로컬 HTTP 실험에서 Secure 쿠키를 명시적으로 전달합니다.
    // 서버의 쿠키 보안 설정은 변경하지 않습니다.
    return {
        'Content-Type': 'application/json',
        [data.headerName]: data.token,
        Cookie: cookies.join('; '),
    };
}

export default function () {
    if (!loginHeaders) {
        loginHeaders = prepareCsrf();
    }

    // 가상 사용자마다 다른 회원을 배정합니다.
    const user = users[__VU - 1];

    attempts.add(1);

    const response = http.post(
        `${BASE}/api/auth/login`,
        JSON.stringify({
            email: user.email,
            password: PASSWORD,
        }),
        {
            headers: loginHeaders,
            tags: { name: '로그인' },
            timeout: '10s',
            redirects: 0,
        }
    );

    duration.add(response.timings.duration);

    const body = jsonOrEmpty(response);
    const data = body.data || {};
    const refreshCookies = response.cookies.refreshToken || [];

    const valid = check(response, {
        '로그인 HTTP 200': () => response.status === 200,
        '로그인 업무 성공': () => body.success === true,
        '접근 토큰 발급': () =>
            typeof data.accessToken === 'string' &&
            data.accessToken.length > 0,
        '토큰 종류 확인': () => data.tokenType === 'Bearer',
        '토큰 유효 시간 확인': () => data.expiresIn === 600,
        '갱신 토큰 쿠키 발급': () => refreshCookies.length > 0,
        '본문에 갱신 토큰 없음': () =>
            !Object.prototype.hasOwnProperty.call(data, 'refreshToken'),
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
            attempts: data.metrics.auth_login_attempts,
            successes: data.metrics.auth_login_successes,
            latency: data.metrics.auth_login_duration,
            failed: data.metrics.auth_login_failed,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}