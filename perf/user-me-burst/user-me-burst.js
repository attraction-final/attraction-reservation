import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { vu } from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const accounts = new SharedArray('burst-accounts', () =>
    JSON.parse(open('./.local/accounts.json'))
);
const vus = Number(__ENV.VUS || 10);
const base = 'http://host.docker.internal:18090';

if (!Number.isInteger(vus) || vus < 1 ||
    vus > 2000 || vus > accounts.length) {
    throw new Error('요청 수에 맞는 전용 회원 토큰을 준비하세요.');
}

const attempts = new Counter('burst_attempts');
const successes = new Counter('burst_successes');
const latency = new Trend('burst_duration', true);
const requestElapsed = new Trend('burst_request_elapsed', true);
const launchOffset = new Trend('burst_launch_offset', true);

export const options = {
    scenarios: {
        user_me: {
            executor: 'per-vu-iterations',
            vus,
            iterations: 1,
            maxDuration: '45s',
            gracefulStop: '0s',
        },
    },
    thresholds: {
        burst_attempts: [`count==${vus}`],
        burst_successes: [`count==${vus}`],
        iterations: [`count==${vus}`],
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        burst_duration: ['p(95)<2000'],
        burst_launch_offset: ['max<1000'],
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(95)', 'p(99)',
    ],
};

export function setup() {
    const seen = new Set();
    for (let index = 0; index < vus; index++) {
        const account = accounts[index];
        if (!account.token ||
            account.expiresAt < Date.now() + 90000) {
            fail('토큰을 다시 발급한 후 실행하세요.');
        }
        if (seen.has(account.expected.id)) {
            fail('회원이 중복 배정됐습니다.');
        }
        seen.add(account.expected.id);
    }
    return { targetTime: Date.now() + 15000 };
}

export default function (schedule) {
    const account = accounts[vu.idInTest - 1];
    const expected = account.expected;

    const wait = schedule.targetTime - Date.now();
    if (wait > 0) {
        sleep(wait / 1000);
    }

    const calledAt = Date.now();
    const response = http.get(`${base}/api/users/me`, {
        headers: {
            Authorization: `Bearer ${account.token}`,
        },
        tags: { name: 'GET /api/users/me' },
        timeout: '10s',
        redirects: 0,
    });

    // HTTP 호출이 반환된 직후 기록한다.
    // JSON 해석과 응답 검증 시간은 포함하지 않는다.
    const elapsedMs = Date.now() - calledAt;

    let body = null;
    try {
        body = response.json();
    } catch (_) {
        // JSON 응답이 아니면 아래 검증에서 실패한다.
    }
    const actual = body && body.data;
    const valid = check(response, {
        'HTTP 200': r => r.status === 200,
        '업무 성공': () => body !== null &&
            body.success === true,
        '본인 정보 및 필드 일치': () =>
            actual !== null &&
            typeof actual === 'object' &&
            JSON.stringify(Object.keys(actual).sort()) ===
                JSON.stringify(Object.keys(expected).sort()) &&
            Object.keys(expected).every(
                key => actual[key] === expected[key]
            ),
    });

    attempts.add(1);
    successes.add(valid ? 1 : 0);
    latency.add(response.timings.duration);
    requestElapsed.add(elapsedMs);
    launchOffset.add(calledAt - schedule.targetTime);
}

export function handleSummary(data) {
    const offsets = data.metrics.burst_launch_offset;
    const values = offsets ? offsets.values : null;
    const report = {
        requestedUsers: vus,
        attempts: data.metrics.burst_attempts,
        successes: data.metrics.burst_successes,
        latency: data.metrics.burst_duration,
        requestElapsed: data.metrics.burst_request_elapsed,
        httpBlocked: data.metrics.http_req_blocked,
        httpConnecting: data.metrics.http_req_connecting,
        httpWaiting: data.metrics.http_req_waiting,
        checks: data.metrics.checks,
        launchOffset: offsets,
        launchSpreadMs: values
            ? values.max - values.min : null,
        note: '호출 시각은 부하 도구 기준이며 서버 도착 시각이 아님.',
    };
    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        '/results/burst-report.json': JSON.stringify(report, null, 2),
        stdout: JSON.stringify(report, null, 2) + '\n',
    };
}
