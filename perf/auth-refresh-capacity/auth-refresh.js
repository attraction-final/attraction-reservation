import http from 'k6/http';
import encoding from 'k6/encoding';
import { check, fail, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const accounts = new SharedArray('재발급 준비 세션', () =>
    JSON.parse(open('./.local/load-accounts.json')).accounts
);

const BASE = 'http://host.docker.internal:18094';
const MODE = __ENV.MODE || 'smoke';
const VUS = Number(__ENV.VUS || 1);

if (!['smoke', 'load'].includes(MODE)) {
    throw new Error('MODE는 smoke 또는 load여야 합니다.');
}

if (!Number.isInteger(VUS) || VUS < 1 || VUS > accounts.length) {
    throw new Error('VUS는 준비된 회원 수 이내의 양의 정수여야 합니다.');
}

const attempts = new Counter('auth_refresh_attempts');
const successes = new Counter('auth_refresh_successes');
const failed = new Rate('auth_refresh_failed');
const duration = new Trend('auth_refresh_duration', true);
const elapsed = new Trend('auth_refresh_elapsed', true);

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

// 준비된 인증 정보는 요청 헤더로만 사용하고 출력하지 않는다.

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

export function setup() {
    const seen = new Set();
    for (const account of accounts.slice(0, VUS)) {
        if (!Number.isSafeInteger(account.userId) ||
            account.userId <= 0 || seen.has(account.userId)) {
            fail('회원 배정이 올바르지 않거나 중복됩니다.');
        }
        if (!account.refreshHeaders ||
            typeof account.refreshHeaders.Cookie !== 'string' ||
            !Number.isFinite(account.absoluteExpiration) ||
            account.absoluteExpiration < Date.now() + 120000) {
            fail('세션을 다시 준비한 후 실행하세요.');
        }
        seen.add(account.userId);
    }
}

export default function () {
    const account = accounts[__VU - 1];
    const refreshHeaders = account.refreshHeaders;

    attempts.add(1);
    const startedAt = Date.now();

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

    const requestElapsed = Date.now() - startedAt;
    duration.add(response.timings.duration);
    elapsed.add(requestElapsed);

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
            String(claims.sub) === String(account.userId) &&
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
            elapsed: data.metrics.auth_refresh_elapsed,
            failed: data.metrics.auth_refresh_failed,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}