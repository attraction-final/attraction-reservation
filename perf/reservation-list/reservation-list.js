import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';
import { BASE, login } from './auth.js';

const runId = __ENV.RUN_ID;
if (!runId || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,59}$/.test(runId)) {
  throw new Error('유효한 RUN_ID가 필요합니다. run.sh를 사용하세요.');
}
const fixture = new SharedArray('expected', () =>
  open(`/results/${runId}/fixture.jsonl`).trim().split(/\r?\n/).map(row => JSON.parse(row)));
const duration = new Trend('reservation_list_duration', true);
const durations = {
  all: new Trend('reservation_list_all_duration', true),
  reserved: new Trend('reservation_list_reserved_duration', true),
};
const invalid = new Rate('reservation_list_invalid');
const requests = new Counter('reservation_list_requests');
// CPU 표본과 인증 준비/실제 조회 시작을 구분하기 위한 UTC epoch ms다. 토큰은 기록하지 않는다.
const setupStartedAt = new Gauge('reservation_list_setup_started_at_ms');
const setupFinishedAt = new Gauge('reservation_list_setup_finished_at_ms');
const vuStartedAt = new Gauge('reservation_list_vu_started_at_ms');

const mode = __ENV.TEST_MODE || 'smoke';
if (!['smoke', 'warmup', 'load'].includes(mode)) throw new Error('잘못된 TEST_MODE');
const loadUsers = Number(__ENV.TEST_VUS || 10);
if (![10, 20, 50].includes(loadUsers)) throw new Error('TEST_VUS는 10, 20, 50이어야 합니다.');
const users = mode === 'smoke' ? 2 : loadUsers;
const p95 = __ENV.P95_MS ? Number(__ENV.P95_MS) : null;
if (p95 !== null && (!Number.isFinite(p95) || p95 <= 0)) throw new Error('P95_MS는 양수여야 합니다.');
const thresholds = {
  checks: ['rate==1'],
  reservation_list_invalid: ['rate==0'],
  reservation_list_requests: [mode === 'smoke' ? 'count==20' : `count>=${users * 2}`],
  'http_req_failed{endpoint:reservation-list}': ['rate==0'],
  'reservation_list_requests{variant:all}': [mode === 'smoke' ? 'count==10' : `count>=${users}`],
  'reservation_list_requests{variant:reserved}': [mode === 'smoke' ? 'count==10' : `count>=${users}`],
};
// 성능 목표는 load에 명시한 경우에만 적용한다. warm-up의 시간 분포는 합격 기준에서 제외한다.
if (mode === 'load' && p95 !== null) {
  thresholds.reservation_list_all_duration = [`p(95)<${p95}`];
  thresholds.reservation_list_reserved_duration = [`p(95)<${p95}`];
}
export const options = {
  scenarios: mode === 'smoke' ? {
    smoke: { executor: 'per-vu-iterations', vus: 2, iterations: 5, maxDuration: '60s', gracefulStop: '5s' },
  } : {
    [mode]: { executor: 'constant-vus', vus: users, duration: mode === 'warmup' ? '20s' : '60s', gracefulStop: '25s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds,
};

export function setup() {
  setupStartedAt.add(Date.now());
  if (fixture.length !== 100) throw new Error('DB 기대 결과는 회원 50명 × 조건 2개의 100행이어야 합니다.');
  const accounts = [];
  for (const row of fixture) {
    if (!['all', 'reserved'].includes(row.variant) || !Array.isArray(row.rows)
      || row.rows.length !== 10 || row.total < 10) throw new Error('DB 기대 결과 형식 확인 실패');
    let account = accounts.find(item => item.userId === row.userId);
    if (!account) {
      account = { userId: row.userId, email: row.email, expected: {} };
      accounts.push(account);
    }
    if (account.expected[row.variant]) throw new Error('중복 기대 결과');
    account.expected[row.variant] = { total: row.total, rows: row.rows };
  }
  if (accounts.length !== 50 || accounts.some(account => !account.expected.all || !account.expected.reserved)
    || new Set(accounts.map(account => account.email)).size !== 50) {
    throw new Error('서로 다른 회원 50명의 조건별 기대 결과가 필요합니다.');
  }
  const response = http.get(`${BASE}/api/reservations/me`, {
    tags: { endpoint: 'auth-preflight' }, timeout: '10s',
    responseCallback: http.expectedStatuses(401),
  });
  if (!check(response, { '인증 없는 목록 조회는 401': r => r.status === 401 })) {
    throw new Error('인증 사전 검증 실패');
  }
  // 각 프로세스의 setup에서 로그인한다. warm-up과 load는 같은 계정을 사용하되 새 토큰을 발급한다.
  const selected = accounts.sort((left, right) => left.email.localeCompare(right.email)).slice(0, users);
  for (const account of selected) account.token = login(account.email);
  setupFinishedAt.add(Date.now());
  return selected;
}

export default function (accounts) {
  // Gauge의 min/max로 각 VU 첫 iteration 시작 시각의 범위를 확인한다.
  if (__ITER === 0) vuStartedAt.add(Date.now());
  // given: VU마다 다른 회원과 DB에서 계산한 기대 결과를 사용한다.
  const account = accounts[__VU - 1];
  for (const variant of ['all', 'reserved']) {
    const expected = account.expected[variant];
    const filter = variant === 'reserved' ? '&status=RESERVED' : '';
    // when: 로그인은 측정 전에 마치고, 실제 Spring Security를 거쳐 조회한다.
    const response = http.get(`${BASE}/api/reservations/me?page=0&size=10${filter}`, {
      headers: { Authorization: `Bearer ${account.token}` }, timeout: '10s',
      tags: { endpoint: 'reservation-list', variant },
    });
    let body;
    try { body = response.json(); } catch (_) { body = null; }
    const data = body && body.data;
    const rows = data && Array.isArray(data.content) ? data.content : [];
    // then: 건수뿐 아니라 소유 회원의 ID·정렬·필터·DTO 매핑까지 비교한다.
    const valid = check(response, {
      'HTTP 200': r => r.status === 200,
      '공통 성공 응답': () => body !== null && body.success === true,
      '첫 페이지와 전체 건수': () => !!data && data.number === 0 && data.size === 10
        && data.totalElements === expected.total && data.totalPages === Math.ceil(expected.total / 10),
      '회원별 예약 ID·정렬·DTO 일치': () => rows.length === 10 && rows.every((row, index) =>
        Object.keys(expected.rows[index]).every(key => row[key] === expected.rows[index][key])),
      '상태 필터 일치': () => variant !== 'reserved' || (rows.length === 10 && rows.every(row => row.status === 'RESERVED')),
    }, { variant });
    requests.add(1, { variant });
    invalid.add(!valid, { variant });
    duration.add(response.timings.duration, { variant });
    durations[variant].add(response.timings.duration);
  }
  sleep(1);
}

export function handleSummary(data) {
  // k6 버전에 따라 setup_data도 전달되므로 집계 필드만 명시적으로 선택한다.
  // 로그인 토큰이 들어 있는 setup_data와 요청/응답 원문은 저장하지 않는다.
  const summary = { metrics: data.metrics, root_group: data.root_group,
    options: data.options, state: data.state };
  const count = data.metrics.reservation_list_requests?.values.count ?? 0;
  const failed = data.metrics.checks?.values.fails ?? 0;
  const lines = [`${mode} 목록 요청: ${count}${mode === 'smoke' ? '/20' : ''}, 실패한 check: ${failed}`];
  for (const variant of ['all', 'reserved']) {
    const values = data.metrics[`reservation_list_${variant}_duration`]?.values;
    if (values && Number.isFinite(values.med)) lines.push(`${variant}: med=${values.med.toFixed(2)}ms p95=${values['p(95)'].toFixed(2)}ms p99=${values['p(99)'].toFixed(2)}ms`);
  }
  return {
    stdout: lines.join('\n') + '\n',
    [`/results/${runId}/${mode === 'warmup' ? 'warmup-summary.json' : 'summary.json'}`]: JSON.stringify(summary, null, 2),
  };
}
