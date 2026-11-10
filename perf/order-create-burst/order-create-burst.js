import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { vu } from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const accounts = new SharedArray('order-burst-accounts', () =>
    JSON.parse(open('./.local/accounts.json'))
);
const fixtures = new SharedArray('order-burst-fixture', () =>
    [JSON.parse(open('./.local/burst-fixture.json'))]
);
const fixture = fixtures[0];
const vus = Number(__ENV.VUS || 10);
const base = 'http://host.docker.internal:18092';

if (!Number.isInteger(vus) || vus < 1 || vus > 2000 ||
    vus > accounts.length || fixture.users !== vus) {
    throw new Error('요청 수·회원 토큰·실행 기준을 확인하세요.');
}

const attempts = new Counter('burst_attempts');
const successes = new Counter('burst_successes');
const outcomes = new Counter('burst_outcomes');
const httpDuration = new Trend('burst_http_duration', true);
const elapsed = new Trend('burst_request_elapsed', true);
const launchOffset = new Trend('burst_launch_offset', true);

export const options = {
    scenarios: {
        order_create: {
            executor: 'per-vu-iterations',
            vus,
            iterations: 1,
            maxDuration: '90s',
            gracefulStop: '0s',
        },
    },
    thresholds: {
        burst_attempts: [`count==${vus}`],
        burst_outcomes: [`count==${vus}`],
        burst_successes: [`count==${vus}`],
        iterations: [`count==${vus}`],
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        burst_launch_offset: ['max<1000'],
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(95)', 'p(99)',
    ],
};

export function setup() {
    const seen = new Set();

    for (const account of accounts.slice(0, vus)) {
        if (!account.token ||
            account.expiresAt < Date.now() + 90000) {
            fail('토큰을 다시 준비한 후 실행하세요.');
        }
        const id = account.expected.id;
        if (!Number.isSafeInteger(id) || id <= 0 || seen.has(id)) {
            fail('회원 배정이 올바르지 않거나 중복됩니다.');
        }
        seen.add(id);
    }

    if (!Number.isSafeInteger(fixture.productId) ||
        fixture.productId <= 0 ||
        fixture.price !== 10000 ||
        fixture.quantity !== 1 ||
        fixture.initialStock < vus) {
        fail('실험 상품·가격·수량·재고 기준을 확인하세요.');
    }

    return { targetTime: Date.now() + 15000 };
}

export default function (schedule) {
    const account = accounts[vu.idInTest - 1];
    const wait = schedule.targetTime - Date.now();

    if (wait > 0) {
        sleep(wait / 1000);
    }

    const calledAt = Date.now();
    attempts.add(1);

    const response = http.post(
        `${base}/api/orders`,
        JSON.stringify({
            visitDate: fixture.visitDate,
            items: [{
                ticketProductId: fixture.productId,
                quantity: fixture.quantity,
            }],
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${account.token}`,
            },
            tags: { name: 'POST /api/orders' },
            responseCallback: http.expectedStatuses(201),
            timeout: '30s',
            redirects: 0,
        }
    );

    // JSON 해석과 응답 검사 전까지의 호출 경과 시간을 기록한다.
    const requestElapsed = Date.now() - calledAt;

    let body = null;
    try {
        body = response.json();
    } catch (_) {
        // JSON 응답이 아니면 아래 검사에서 실패한다.
    }

    const order = body && body.data ? body.data : {};
    const orderId = order.orderId;
    const validId = Number.isSafeInteger(orderId) && orderId > 0;

    const valid = check(response, {
        '생성 HTTP 201': r => r.status === 201,
        '생성 업무 성공': () =>
            body !== null && body.success === true,
        '생성 주문 ID': () => validId,
        '생성 PENDING 상태': () => order.status === 'PENDING',
        '생성 총금액': () =>
            order.totalAmount === fixture.price * fixture.quantity,
        '생성 주문 항목': () =>
            Array.isArray(order.items) &&
            order.items.length === 1 &&
            Number.isSafeInteger(order.items[0].id) &&
            order.items[0].id > 0 &&
            order.items[0].ticketProductId === fixture.productId &&
            order.items[0].price === fixture.price &&
            order.items[0].quantity === fixture.quantity,
        '생성 결제 식별자': () =>
            typeof order.pgId === 'string' &&
            order.pgId.length > 0,
    });

    successes.add(valid ? 1 : 0);
    httpDuration.add(response.timings.duration);
    elapsed.add(requestElapsed);
    launchOffset.add(calledAt - schedule.targetTime);

    // DB 대조용 식별 정보만 기록한다. 토큰과 응답 원문은 저장하지 않는다.
    outcomes.add(1, {
        user_id: String(account.expected.id),
        order_id: validId ? String(orderId) : '0',
        http_status: String(response.status),
        valid: valid ? 'true' : 'false',
        error_code: String(response.error_code || 0),
    });
}

export function handleSummary(data) {
    const metrics = data.metrics;
    const offset = metrics.burst_launch_offset;
    const values = offset ? offset.values : null;

    const report = {
        requestedUsers: vus,
        attempts: metrics.burst_attempts,
        successes: metrics.burst_successes,
        outcomes: metrics.burst_outcomes,
        elapsed: metrics.burst_request_elapsed,
        httpDuration: metrics.burst_http_duration,
        checks: metrics.checks,
        httpFailed: metrics.http_req_failed,
        launchOffset: offset,
        launchSpreadMs: values ? values.max - values.min : null,
        note: '호출 시각은 부하 도구 기준이며 서버 도착 시각이 아님.',
    };

    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        '/results/burst-report.json': JSON.stringify(report, null, 2),
        stdout: JSON.stringify(report, null, 2) + '\n',
    };
}
