import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const fixture = new SharedArray('사용자 배정', () =>
    JSON.parse(open('./.local/service-fixture.json')).accounts
);
const sessions = new SharedArray('접근 토큰', () =>
    JSON.parse(open('./.local/accounts.json'))
);
const product = new SharedArray('생성 취소용 상품', () => [
    JSON.parse(open('./.local/service-fixture.json')).writeProductId,
])[0];

const base = 'http://host.docker.internal:18096';
const mode = __ENV.MODE || 'smoke';
const vus = Number(__ENV.VUS || 10);
const duration = __ENV.DURATION || '120s';

if (!['smoke', 'load'].includes(mode) ||
    !['120s', '300s'].includes(duration) ||
    !Number.isInteger(vus) || vus < 10 || vus > 2000 ||
    vus % 10 !== 0 || vus > fixture.length ||
    vus > sessions.length) {
    throw new Error('실행 조건과 회원 준비 수를 확인하세요.');
}



const invalid = new Rate('service_invalid');
const completed = new Counter('service_actions_completed');
const listAttempts = new Counter('service_list_attempts');
const meAttempts = new Counter('service_me_attempts');
const createAttempts = new Counter('service_create_attempts');
const created = new Counter('service_created');
const cancelAttempts = new Counter('service_cancel_attempts');
const cancelled = new Counter('service_cancelled');
const outcomes = new Counter('service_order_outcomes');

const listTime = new Trend('service_list_http', true);
const meTime = new Trend('service_me_http', true);
const createTime = new Trend('service_create_http', true);
const cancelTime = new Trend('service_cancel_http', true);
const actionTime = new Trend('service_action_elapsed', true);

const thresholds = {
    checks: ['rate==1'],
    http_req_failed: ['rate==0'],
    service_invalid: ['rate==0'],
    service_actions_completed: ['count>0'],
    service_list_attempts: ['count>0'],
    service_me_attempts: ['count>0'],
    service_create_attempts: ['count>0'],
    service_created: ['count>0'],
    service_cancelled: ['count>0'],
};
if (mode === 'load') {
    thresholds.service_list_http = ['p(95)<=1000'];
    thresholds.service_me_http = ['p(95)<=1000'];
    thresholds.service_create_http = ['p(95)<=2000'];
    thresholds.service_cancel_http = ['p(95)<=2000'];
}

export const options = {
    scenarios: {
        service: mode === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus,
                iterations: 2,
                maxDuration: '60s',
                gracefulStop: '30s',
            }
            : {
                executor: 'constant-vus',
                vus,
                duration,
                gracefulStop: '30s',
            },
    },
    thresholds,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
};

function bodyOf(response) {
    try {
        return response.json();
    } catch (_) {
        return null;
    }
}

function optionsFor(token, name) {
    return {
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json',
        },
        tags: { name },
        timeout: '10s',
        redirects: 0,
    };
}

export function setup() {
    const selected = fixture.slice(0, vus);
    if (new Set(selected.map(a => a.userId)).size !== vus ||
        selected.some((a, index) =>
            a.userId !== sessions[index].expected.id ||
            a.group !== (
                index % 10 < 7 ? 'order_list'
                : index % 10 < 9 ? 'user_me' : 'order_write'
            )
        )) {
        throw new Error('회원 순서 또는 사용자 배정이 다릅니다.');
    }

    const required = mode === 'load'
        ? Number(duration.slice(0, -1)) * 1000 + 90000
        : 120000;
    if (sessions.slice(0, vus).some(a =>
        !a.token || !Number.isFinite(a.expiresAt) ||
        a.expiresAt < Date.now() + required
    )) {
        fail('토큰 잔여 시간이 부족합니다. 다시 준비하세요.');
    }
    if (!Number.isSafeInteger(product) || product <= 0) {
        fail('생성·취소용 상품 ID가 올바르지 않습니다.');
    }
    const visitDate = new Date(
        Date.now() + 9 * 3600000 + 86400000
    ).toISOString().slice(0, 10);
    return { visitDate };
}

export default function (schedule) {
    const account = fixture[__VU - 1];
    const token = sessions[__VU - 1].token;
    const started = Date.now();
    let valid = false;

    try {
        if (account.group === 'order_list') {
            const pages = [0, 5, 9];
            const page = pages[__ITER % pages.length];
            listAttempts.add(1);
            const response = http.get(
                `${base}/api/orders/me?page=${page}&size=10`,
                optionsFor(token, 'GET /api/orders/me')
            );
            listTime.add(response.timings.duration);
            const body = bodyOf(response);
            const data = body && body.data;
            valid = check(response, {
                '목록 HTTP 200': r => r.status === 200,
                '목록 업무 성공': () => body && body.success === true,
                '목록 페이지와 건수': () => data &&
                    data.page === page && data.size === 10 &&
                    data.totalElements === account.total &&
                    data.totalPages === 10,
                '목록 주문과 상품 일치': () => data &&
                    Array.isArray(data.content) &&
                    JSON.stringify(data.content.map(row => row.orderId)) ===
                        JSON.stringify(account.pages[String(page)]) &&
                    data.content.every(row =>
                        row.totalAmount === 10000 &&
                        row.status === 'CANCELLED' &&
                        Array.isArray(row.items) &&
                        row.items.length === 1 &&
                        row.items[0].ticketProductName ===
                            'PERF_SERVICE_HISTORY' &&
                        Number.isSafeInteger(row.items[0].ticketProductId) &&
                        row.items[0].quantity === 1
                    ),
            });
        } else if (account.group === 'user_me') {
            meAttempts.add(1);
            const response = http.get(
                `${base}/api/users/me`,
                optionsFor(token, 'GET /api/users/me')
            );
            meTime.add(response.timings.duration);
            const body = bodyOf(response);
            const data = body && body.data;
            valid = check(response, {
                '회원 HTTP 200': r => r.status === 200,
                '회원 업무 성공': () => body && body.success === true,
                '본인 정보와 필드 일치': () => data &&
                    JSON.stringify(Object.keys(data).sort()) ===
                        JSON.stringify(Object.keys(account.expected).sort()) &&
                    Object.keys(account.expected).every(
                        key => data[key] === account.expected[key]
                    ),
            });
        } else {
            createAttempts.add(1);
            const response = http.post(
                `${base}/api/orders`,
                JSON.stringify({
                    visitDate: schedule.visitDate,
                    items: [{ ticketProductId: product, quantity: 1 }],
                }),
                optionsFor(token, 'POST /api/orders')
            );
            createTime.add(response.timings.duration);
            const body = bodyOf(response);
            const order = (body && body.data) || {};
            const identifiable = response.status === 201 &&
                body && body.success === true &&
                Number.isSafeInteger(order.orderId) && order.orderId > 0;
            const createValid = check(response, {
                '생성 HTTP 201': r => r.status === 201,
                '생성 업무 성공과 주문 ID': () => Boolean(identifiable),
                '생성 상태와 금액': () =>
                    order.status === 'PENDING' && order.totalAmount === 10000,
                '생성 상품과 수량': () =>
                    Array.isArray(order.items) && order.items.length === 1 &&
                    order.items[0].ticketProductId === product &&
                    order.items[0].price === 10000 &&
                    order.items[0].quantity === 1 &&
                    Number.isSafeInteger(order.items[0].id) &&
                    order.items[0].id > 0,
                '생성 결제 식별자': () =>
                    typeof order.pgId === 'string' && order.pgId.length > 0,
            });
            created.add(identifiable ? 1 : 0);
            if (!identifiable) {
                return;
            }
            outcomes.add(1, {
                user_id: String(account.userId),
                order_id: String(order.orderId),
                stage: 'created',
            });

            // 생성 응답의 다른 검사가 실패해도 식별한 주문은 취소한다.
            cancelAttempts.add(1);
            const cancelResponse = http.post(
                `${base}/api/orders/${order.orderId}/cancel`,
                null,
                optionsFor(token, 'POST /api/orders/{orderId}/cancel')
            );
            cancelTime.add(cancelResponse.timings.duration);
            const cancelBody = bodyOf(cancelResponse);
            const result = cancelBody && cancelBody.data;
            const cancelValid = check(cancelResponse, {
                '취소 HTTP 200': r => r.status === 200,
                '취소 업무 성공': () =>
                    cancelBody && cancelBody.success === true,
                '취소 주문과 상태 일치': () => result &&
                    result.orderId === order.orderId &&
                    result.status === 'CANCELLED',
            });
            cancelled.add(cancelValid ? 1 : 0);
            if (cancelValid) {
                outcomes.add(1, {
                    user_id: String(account.userId),
                    order_id: String(order.orderId),
                    stage: 'cancelled',
                });
            }
            valid = createValid && cancelValid;
        }
    } finally {
        actionTime.add(Date.now() - started, { group: account.group });
        invalid.add(!valid, { group: account.group });
        completed.add(valid ? 1 : 0, { group: account.group });
        sleep(mode === 'smoke' ? 0.2 : 3 + Math.random() * 2);
    }
}

export function handleSummary(data) {
    return {
        '/results/summary.json': JSON.stringify(data, null, 2),
        stdout: JSON.stringify({
            mode, vus, duration: mode === 'load' ? duration : null,
            note: '사용자 배정 비율은 70:20:10이며 HTTP 요청 비율과 다름.',
            actions: data.metrics.service_actions_completed,
            invalid: data.metrics.service_invalid,
            list: data.metrics.service_list_http,
            me: data.metrics.service_me_http,
            create: data.metrics.service_create_http,
            cancel: data.metrics.service_cancel_http,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}
