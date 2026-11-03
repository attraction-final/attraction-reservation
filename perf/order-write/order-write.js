import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate, Counter } from 'k6/metrics';

const accounts = new SharedArray('write-accounts', () =>
    JSON.parse(open('./.local/load-accounts.json'))
);

const fixtures = new SharedArray('write-fixture', () =>
    [JSON.parse(open('./.local/load-fixture.json'))]
);

const fixture = fixtures[0];
const base = 'http://host.docker.internal:18086';
const mode = __ENV.MODE || 'smoke';
const vus = Number(__ENV.VUS || 2);

if (!['smoke', 'load'].includes(mode)) {
    throw new Error('MODE는 smoke 또는 load여야 합니다.');
}
if (
    !Number.isInteger(vus) ||
    vus < 1 ||
    vus > 10 ||
    vus > accounts.length
) {
    throw new Error('서로 다른 계정으로 VU 1~10만 지원합니다.');
}

const selectedIds = accounts.slice(0, vus).map(a => a.userId);

if (new Set(selectedIds).size !== vus) {
    throw new Error('VU에 배정된 회원이 중복됩니다.');
}
if (selectedIds.some(id => !fixture.userIds.includes(id))) {
    throw new Error('검증 기준에 없는 회원입니다.');
}

const createDuration = new Trend('order_create_duration', true);
const cancelDuration = new Trend('order_cancel_duration', true);
const flowDuration = new Trend('order_flow_duration', true);
const flowFailed = new Rate('order_flow_failed');

const createAttempts = new Counter('order_create_attempts');
const created = new Counter('order_created');
const cancelAttempts = new Counter('order_cancel_attempts');
const cancelled = new Counter('order_cancelled');
const completed = new Counter('order_flow_completed');

export const options = {
    scenarios: {
        order_write: mode === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus,
                iterations: 3,
                maxDuration: '30s',
                gracefulStop: '30s',
            }
            : {
                executor: 'constant-vus',
                vus,
                duration: '60s',
                gracefulStop: '30s',
            },
    },
    thresholds: {
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
        order_flow_failed: ['rate==0'],
        order_flow_completed: [
            mode === 'smoke' ? `count==${vus * 3}` : 'count>0',
        ],
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)',
    ],
};

function readBody(response) {
    try {
        return response.json();
    } catch (_) {
        return null;
    }
}

export function setup() {
    if (accounts.slice(0, vus).some(
        a => Date.now() + 120000 >= a.expiresAt
    )) {
        fail('토큰 잔여 시간이 부족합니다. 준비 스크립트를 다시 실행하세요.');
    }

    const response = http.get(`${base}/api/orders/me`, {
        responseCallback: http.expectedStatuses(401),
        tags: { name: 'order_write_unauthorized_preflight' },
        timeout: '10s',
    });

    if (response.status !== 401) {
        fail('미인증 요청이 401로 거절되지 않았습니다.');
    }
}

export default function () {
    const account = accounts[__VU - 1];
    const headers = {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${account.token}`,
    };

    const started = Date.now();
    let failed = true;

    try {
        createAttempts.add(1);
        cancelAttempts.add(0);
        cancelled.add(0);

        const createResponse = http.post(
            `${base}/api/orders`,
            JSON.stringify({
                visitDate: fixture.visitDate,
                items: [{
                    ticketProductId: fixture.productId,
                    quantity: fixture.quantity,
                }],
            }),
            {
                headers,
                tags: { name: 'POST /api/orders' },
                timeout: '10s',
            }
        );

        createDuration.add(createResponse.timings.duration);

        const createBody = readBody(createResponse);
        const order = createBody && createBody.data
            ? createBody.data
            : {};
        const orderId = order.orderId;
        const validId = Number.isSafeInteger(orderId) && orderId > 0;

        const createValid = check(createResponse, {
            '생성 HTTP 201': r => r.status === 201,
            '생성 업무 성공': () =>
                createBody !== null && createBody.success === true,
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
                typeof order.pgId === 'string' && order.pgId.length > 0,
        });

        const canCancel = (
            createResponse.status === 201 &&
            createBody !== null &&
            createBody.success === true &&
            validId
        );

        created.add(canCancel ? 1 : 0);

        if (!canCancel) {
            return;
        }

        // 다른 응답 검증이 실패해도 주문 ID를 확인했다면 취소를 시도한다.
        // 검증 실패 사실은 아래 failed 값에 유지한다.
        cancelAttempts.add(1);

        const cancelResponse = http.post(
            `${base}/api/orders/${orderId}/cancel`,
            null,
            {
                headers,
                tags: { name: 'POST /api/orders/{orderId}/cancel' },
                timeout: '10s',
            }
        );

        cancelDuration.add(cancelResponse.timings.duration);

        const cancelBody = readBody(cancelResponse);
        const result = cancelBody && cancelBody.data
            ? cancelBody.data
            : {};

        const cancelValid = check(cancelResponse, {
            '취소 HTTP 200': r => r.status === 200,
            '취소 업무 성공': () =>
                cancelBody !== null && cancelBody.success === true,
            '취소 주문 ID 일치': () => result.orderId === orderId,
            '취소 CANCELLED 상태': () => result.status === 'CANCELLED',
        });

        cancelled.add(cancelValid ? 1 : 0);
        failed = !(createValid && cancelValid);
    } finally {
        // 전체 흐름 시간에는 두 요청과 사이의 클라이언트 처리가 포함된다.
        // 마지막 1초 대기는 제외한다.
        flowDuration.add(Date.now() - started);
        flowFailed.add(failed);
        completed.add(failed ? 0 : 1);
        sleep(1);
    }
}

export function handleSummary(data) {
    const metrics = data.metrics;

    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        stdout: JSON.stringify({
            mode,
            vus,
            createAttempts: metrics.order_create_attempts,
            created: metrics.order_created,
            cancelAttempts: metrics.order_cancel_attempts,
            cancelled: metrics.order_cancelled,
            completed: metrics.order_flow_completed,
            createLatency: metrics.order_create_duration,
            cancelLatency: metrics.order_cancel_duration,
            flowLatency: metrics.order_flow_duration,
            flowFailed: metrics.order_flow_failed,
            checks: metrics.checks,
        }, null, 2) + '\n',
    };
}