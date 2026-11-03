import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const accountSet = __ENV.ACCOUNT_SET || 'baseline';

if (!['baseline', 'load'].includes(accountSet)) {
    throw new Error('ACCOUNT_SET은 baseline 또는 load여야 합니다.');
}

const accounts = new SharedArray(`accounts-${accountSet}`, () =>
    JSON.parse(open(
        accountSet === 'load'
            ? './.local/load-accounts.json'
            : '../order-list/.local/accounts.json'
    ))
);

const users = new SharedArray(`user-fixtures-${accountSet}`, () =>
    JSON.parse(open(
        accountSet === 'load'
            ? './.local/load-user-fixture.json'
            : './.local/user-fixture.json'
    ))
);

const base = __ENV.BASE_URL || 'http://host.docker.internal:18086';
const mode = __ENV.MODE || 'smoke';
const vus = Number(__ENV.VUS || 2);
const duration = __ENV.DURATION || '60s';

if (!['smoke', 'load'].includes(mode)) {
    throw new Error('MODE는 smoke 또는 load여야 합니다.');
}
if (!Number.isInteger(vus) || vus < 1 || vus > accounts.length) {
    throw new Error('VU마다 서로 다른 계정이 필요합니다.');
}
if (!['30s', '60s', '90s'].includes(duration)) {
    throw new Error('DURATION은 30s, 60s, 90s만 지원합니다.');
}

// 회원 검증 기준을 계정 순서에 맞춰 한 번 준비하고 공유한다.
const expectedUsers = new SharedArray(
    `expected-users-${accountSet}-${vus}`,
    () => {
        const byId = new Map();
        for (const user of users) {
            byId.set(user.id, user);
        }

        const seen = new Set();
        const expected = [];

        for (let index = 0; index < vus; index++) {
            const account = accounts[index];

            if (seen.has(account.userId)) {
                throw new Error('VU에 배정된 계정이 중복됩니다.');
            }
            seen.add(account.userId);

            const user = byId.get(account.userId);
            if (!user) {
                throw new Error(
                    '계정에 대응하는 회원 검증 기준이 없습니다.'
                );
            }

            expected.push(user);
        }

        return expected;
    }
);

const latency = new Trend('user_me_duration', true);
const invalid = new Rate('user_me_invalid');

export const options = {
    scenarios: {
        user_me: mode === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus,
                iterations: 5,
                maxDuration: '30s',
            }
            : {
                executor: 'constant-vus',
                vus,
                duration,
                gracefulStop: '10s',
            },
    },
    thresholds: {
        user_me_invalid: ['rate==0'],
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],

        ...(accountSet === 'load' && mode === 'load' ? {
            user_me_duration: [{
                threshold: 'p(95)<2000',
                abortOnFail: true,
                delayAbortEval: '30s',
            }],
            http_req_failed: [
                'rate==0',
                {
                    threshold: 'rate<0.05',
                    abortOnFail: true,
                    delayAbortEval: '30s',
                },
            ],
        } : {}),
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)',
    ],
};

export function setup() {
    if (accounts.slice(0, vus).some(
        a => Date.now() + 120000 >= a.expiresAt
    )) {
        fail('토큰을 다시 발급한 후 실행해주세요.');
    }

    const response = http.get(`${base}/api/users/me`, {
        tags: { name: 'user_me_unauthorized_preflight' },
        responseCallback: http.expectedStatuses(401),
        timeout: '10s',
    });

    if (response.status !== 401) {
        fail('미인증 내 정보 조회가 401로 거부되지 않았습니다.');
    }
}

export default function () {
    const account = accounts[__VU - 1];
    const expected = expectedUsers[__VU - 1];

    const response = http.get(`${base}/api/users/me`, {
        headers: { Authorization: `Bearer ${account.token}` },
        tags: {
            name: 'GET /api/users/me',
            endpoint: 'user_me',
        },
        timeout: '10s',
    });

    let body;
    try {
        body = response.json();
    } catch (_) {
        body = null;
    }

    const data = body && body.data ? body.data : {};

    const valid = check(response, {
        'HTTP 200': r => r.status === 200,
        '업무 성공': () => body !== null && body.success === true,
        '본인 회원 ID 일치': () =>
            data.id === account.userId && data.id === expected.id,
        '이메일 일치': () => data.email === expected.email,
        '닉네임 일치': () => data.nickname === expected.nickname,
        '권한과 상태 일치': () =>
            data.role === expected.role && data.status === expected.status,
        '응답 필드 구성 일치': () =>
            JSON.stringify(Object.keys(data).sort()) ===
            JSON.stringify(['email', 'id', 'nickname', 'role', 'status']),
    });

    latency.add(response.timings.duration);
    invalid.add(!valid);
    sleep(1);
}

export function handleSummary(data) {
    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        stdout: JSON.stringify({
            mode,
            vus,
            thinkSeconds: 1,
            note: '로컬 내 정보 조회 검증. 최대 처리 능력의 증거가 아님.',
            latency: data.metrics.user_me_duration,
            invalid: data.metrics.user_me_invalid,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}