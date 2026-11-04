import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const accounts = new SharedArray('admin-accounts', () =>
    JSON.parse(open('./.local/accounts.json'))
);

const fixtures = new SharedArray('admin-fixture', () =>
    [JSON.parse(open('./.local/fixture.json'))]
);

const fixture = fixtures[0];
const pageNumbers = Object.keys(fixture.pages)
    .map(Number)
    .sort((a, b) => a - b);

const base = 'http://host.docker.internal:18086';
const mode = __ENV.MODE || 'smoke';
const vus = Number(__ENV.VUS || 1);

if (!['smoke', 'load'].includes(mode)) {
    throw new Error('MODE는 smoke 또는 load여야 합니다.');
}
if (!Number.isInteger(vus) || vus < 1 || vus > 10) {
    throw new Error('이번 실험은 VU 1~10만 지원합니다.');
}

const latency = new Trend('admin_list_duration', true);

const firstLatency = new Trend('admin_list_first_duration', true);
const middleLatency = new Trend('admin_list_middle_duration', true);
const lastLatency = new Trend('admin_list_last_duration', true);

const invalid = new Rate('admin_list_invalid');

export const options = {
    scenarios: {
        admin_list: mode === 'smoke'
            ? {
                executor: 'per-vu-iterations',
                vus,
                iterations: 6,
                maxDuration: '30s',
            }
            : {
                executor: 'constant-vus',
                vus,
                duration: '60s',
                gracefulStop: '10s',
            },
    },
    thresholds: {
        checks: ['rate==1'],
        admin_list_invalid: ['rate==0'],
        http_req_failed: ['rate==0'],
    },
    summaryTrendStats: [
        'avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)',
    ],
};

export function setup() {
    if (Date.now() + 120000 >= accounts[0].expiresAt) {
        fail('토큰 잔여 시간이 부족합니다. 준비 스크립트를 다시 실행하세요.');
    }

    const response = http.get(
        `${base}/api/admin/users?page=0&size=${fixture.size}`,
        {
            responseCallback: http.expectedStatuses(401),
            tags: { name: 'admin_list_unauthorized_preflight' },
            timeout: '10s',
        }
    );

    if (response.status !== 401) {
        fail('미인증 요청이 401로 거절되지 않았습니다.');
    }
}

export default function () {
    const page = pageNumbers[__ITER % pageNumbers.length];
    const expected = fixture.pages[String(page)].content;

    const response = http.get(
        `${base}/api/admin/users?page=${page}&size=${fixture.size}`,
        {
            headers: {
                Authorization: `Bearer ${accounts[0].token}`,
            },
            tags: {
                name: 'GET /api/admin/users',
                page: String(page),
            },
            timeout: '10s',
        }
    );

    let body = null;
    try {
        body = response.json();
    } catch (_) {
        // JSON 응답이 아니면 아래 검증에서 실패한다.
    }

    const data = body && body.data ? body.data : {};
    const content = Array.isArray(data.content) ? data.content : [];

    const valid = check(response, {
        'HTTP 200': r => r.status === 200,
        '업무 성공': () => body !== null && body.success === true,
        '페이지 번호와 크기': () =>
            data.page === page && data.size === fixture.size,
        '전체 건수와 페이지 수': () =>
            data.totalElements === fixture.totalElements &&
            data.totalPages === fixture.totalPages,
        '페이지별 회원 수': () =>
            content.length === expected.length,
        '회원 ID와 정렬 순서': () =>
            JSON.stringify(content.map(u => u.id)) ===
            JSON.stringify(expected.map(u => u.id)),
        '회원 정보 일치': () =>
            content.length === expected.length &&
            content.every((u, i) =>
                ['id', 'email', 'nickname', 'role', 'status']
                    .every(key => u[key] === expected[i][key])
            ),
        '응답 필드 구성': () =>
            content.length === expected.length &&
            content.every(u =>
                JSON.stringify(Object.keys(u).sort()) ===
                JSON.stringify([
                    'createdAt', 'email', 'id',
                    'nickname', 'role', 'status',
                ]) &&
                typeof u.createdAt === 'string'
            ),
    });

    const duration = response.timings.duration;

    latency.add(duration, { page: String(page) });

    if (page === pageNumbers[0]) {
        firstLatency.add(duration);
    } else if (page === pageNumbers[pageNumbers.length - 1]) {
        lastLatency.add(duration);
    } else {
        middleLatency.add(duration);
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
            accountCount: 1,
            latency: data.metrics.admin_list_duration,
            firstLatency: data.metrics.admin_list_first_duration,
            middleLatency: data.metrics.admin_list_middle_duration,
            lastLatency: data.metrics.admin_list_last_duration,
            invalid: data.metrics.admin_list_invalid,
            checks: data.metrics.checks,
        }, null, 2) + '\n',
    };
}