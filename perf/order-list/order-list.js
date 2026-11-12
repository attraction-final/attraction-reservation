import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const accounts = new SharedArray('accounts', () => JSON.parse(open('./.local/accounts.json')));
const base = __ENV.BASE_URL || 'http://host.docker.internal:18086';
const mode = __ENV.MODE || 'smoke';
const vus = Number(__ENV.VUS || 2);
const thinkTime = Number(__ENV.THINK_SECONDS || 1);
const duration = __ENV.DURATION || '60s';
if (!['30s', '60s', '90s'].includes(duration)) throw new Error('초기 버전 DURATION은 30s, 60s, 90s만 지원');
if (!['smoke', 'load'].includes(mode)) throw new Error('MODE는 smoke 또는 load');
if (!Number.isInteger(vus) || vus < 1 || vus > accounts.length) throw new Error('VU마다 서로 다른 계정이 필요합니다.');
if (!Number.isFinite(thinkTime) || thinkTime < 0) throw new Error('요청 간격은 0 이상');
const listDuration = new Trend('order_list_duration', true);
const invalid = new Rate('order_list_invalid');
export const options = {
  scenarios: { order_list: mode === 'smoke'
    ? { executor: 'per-vu-iterations', vus, iterations: 5, maxDuration: '30s' }
    : { executor: 'constant-vus', vus, duration, gracefulStop: '10s' } },
  thresholds: { order_list_invalid: ['rate==0'], checks: ['rate==1'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};
export function setup() {
  // 초기 스크립트는 2분 이내 측정용. 긴 실행은 토큰 수명/재발급 모델을 먼저 설계한다.
  if (accounts.some(a => Date.now() + 120000 >= a.expiresAt)) fail('토큰을 다시 발급한 후 실행하세요.');
  const r = http.get(`${base}/api/orders/me?page=0&size=10`, {
    tags: { name: 'unauthorized_preflight' }, responseCallback: http.expectedStatuses(401),
  });
  if (r.status !== 401) fail('미인증 요청이 401로 거부되지 않았습니다.');
}
export default function () {
  const a = accounts[__VU - 1];
  const pages = [0, Math.floor(a.total / 20), Math.max(0, Math.ceil(a.total / 10) - 1)];
  const page = pages[__ITER % pages.length];
  const r = http.get(`${base}/api/orders/me?page=${page}&size=10`, {
    headers: { Authorization: `Bearer ${a.token}` },
    tags: { name: 'GET /api/orders/me', endpoint: 'order_list', page_group: String(__ITER % pages.length) },
    timeout: '10s',
  });
  let body; try { body = r.json(); } catch (_) { body = {}; }
  const data = body.data || {};
  const rows = data.content || [];
  const expected = a.pages[String(page)];
  const valid = check(r, {
    'HTTP 200': x => x.status === 200,
    '업무 성공': () => body.success === true,
    '페이지와 전체 건수 일치': () => data.page === page && data.size === 10 && data.totalElements === a.total && data.totalPages === Math.ceil(a.total / 10),
    'DB 기준 주문 ID와 순서 일치': () => Array.isArray(expected) && JSON.stringify(rows.map(x => x.orderId)) === JSON.stringify(expected),
    '금액과 상태 일치': () => rows.every(x => x.totalAmount === 10000 && x.status === 'COMPLETED'),
  });
  listDuration.add(r.timings.duration);
  invalid.add(!valid);
  sleep(thinkTime);
}
export function handleSummary(data) {
  return {
    '/results/summary.json': JSON.stringify(data, null, 2),
    stdout: JSON.stringify({ mode, vus, duration, thinkTime,
      note: '로컬 준비 검증. 2000명 수용 능력 또는 개선 효과의 증거가 아님.',
      latency: data.metrics.order_list_duration,
      invalid: data.metrics.order_list_invalid,
      requests: data.metrics.http_reqs,
      checks: data.metrics.checks,
    }, null, 2) + '\n',
  };
}
