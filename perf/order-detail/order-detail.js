import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const accounts = new SharedArray('accounts', () =>
    JSON.parse(open('../order-list/.local/accounts.json'))
);
const fixtures = new SharedArray('detail-fixtures', () =>
    JSON.parse(open('./.local/detail-fixture.json'))
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

const byUser = new Map();
for (const fixture of fixtures) {
    const orders = byUser.get(fixture.userId) || [];
    orders.push(fixture);
    byUser.set(fixture.userId, orders);
}

for (const account of accounts.slice(0, vus)) {
    const orders = byUser.get(account.userId) || [];
    if (orders.length !== 2) {
        throw new Error('회원별 상세 조회 대상 주문이 2건이어야 합니다.');
    }
}

const detailDuration = new Trend('order_detail_duration', true);
const singleDuration = new Trend('order_detail_single_duration', true);
const multiDuration = new Trend('order_detail_multi_duration', true);
const invalid = new Rate('order_detail_invalid');

export const options = {
    scenarios: {
        order_detail: mode === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus,
                iterations: 6,
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
        order_detail_invalid: ['rate==0'],
        checks: ['rate==1'],
        http_req_failed: ['rate==0'],
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)',
    ],
};

export function setup() {
    const activeAccounts = accounts.slice(0, vus);

    if (activeAccounts.some(a => Date.now() + 120000 >= a.expiresAt)) {
        fail('토큰을 다시 발급한 후 실행해주세요.');
    }

    const owner = activeAccounts[0];
    const expected = byUser.get(owner.userId)[0];
    const other = accounts.find(a => a.userId !== owner.userId);

    if (!other || Date.now() + 120000 >= other.expiresAt) {
        fail('다른 회원의 권한 검증에 사용할 토큰이 필요합니다.');
    }

    const anonymous = http.get(`${base}/api/orders/${expected.orderId}`, {
        tags: { name: 'detail_unauthorized_preflight' },
        responseCallback: http.expectedStatuses(401),
        timeout: '10s',
    });
    if (anonymous.status !== 401) {
        fail('미인증 상세 조회가 401로 거부되지 않았습니다.');
    }

    const forbidden = http.get(`${base}/api/orders/${expected.orderId}`, {
        headers: { Authorization: `Bearer ${other.token}` },
        tags: { name: 'detail_forbidden_preflight' },
        responseCallback: http.expectedStatuses(403),
        timeout: '10s',
    });
    if (forbidden.status !== 403) {
        fail('다른 회원의 주문 상세 조회가 403으로 거부되지 않았습니다.');
    }
}

function normalizeItems(items) {
    if (!Array.isArray(items)) return null;

    return items.map(item => ({
        id: item.id,
        ticketProductId: item.ticketProductId,
        price: item.price,
        quantity: item.quantity,
    })).sort((a, b) => a.id - b.id);
}

export default function () {
    const account = accounts[__VU - 1];
    const orders = byUser.get(account.userId);
    const expected = orders[__ITER % orders.length];
    const group = expected.items.length === 1 ? 'single' : 'multi';

    const response = http.get(
        `${base}/api/orders/${expected.orderId}`,
        {
            headers: { Authorization: `Bearer ${account.token}` },
            tags: {
                name: 'GET /api/orders/{orderId}',
                endpoint: 'order_detail',
                item_group: group,
            },
            timeout: '10s',
        }
    );

    let body;
    try {
        body = response.json();
    } catch (_) {
        body = {};
    }

    const data = body && body.data ? body.data : {};
    const items = normalizeItems(data.items);
    const expectedItems = normalizeItems(expected.items);

    const valid = check(response, {
        'HTTP 200': r => r.status === 200,
        '업무 성공': () => body != null && body.success === true,
        '주문 ID 일치': () => data.orderId === expected.orderId,
        '금액과 상태 일치': () =>
            data.totalAmount === expected.totalAmount &&
            data.status === expected.status,
        '주문 항목 전체 일치': () =>
            items !== null &&
            JSON.stringify(items) === JSON.stringify(expectedItems),
        '주문 금액과 항목 합계 일치': () =>
            items !== null &&
            items.reduce((sum, item) =>
                sum + item.price * item.quantity, 0) === data.totalAmount,
    });

    detailDuration.add(response.timings.duration);
    if (group === 'single') {
        singleDuration.add(response.timings.duration);
    } else {
        multiDuration.add(response.timings.duration);
    }
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
            note: '로컬 상세 조회 검증. 최대 처리 능력의 증거가 아님.',
            latency: data.metrics.order_detail_duration,
            singleLatency: data.metrics.order_detail_single_duration,
            multiLatency: data.metrics.order_detail_multi_duration,
            invalid: data.metrics.order_detail_invalid,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}