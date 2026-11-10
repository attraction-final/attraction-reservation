import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import execution from 'k6/execution';
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
const scenarioStartedAt = new Gauge('reservation_list_scenario_started_at_ms');
const firstRequestAt = new Gauge('reservation_list_vu_first_request_at_ms');
// load의 전체 지표를 유지하면서 요청 시작 시각으로 구간을 나눈다.
const phaseMetrics = {};
for (const phase of ['initial', 'later', 'drain']) {
  phaseMetrics[phase] = {};
  for (const variant of ['all', 'reserved']) {
    const prefix = `reservation_list_${phase}_${variant}`;
    phaseMetrics[phase][variant] = {
      duration: new Trend(`${prefix}_duration`, true),
      requests: new Counter(`${prefix}_requests`),
      invalid: new Rate(`${prefix}_invalid`),
    };
  }
}

const mode = __ENV.TEST_MODE || 'smoke';
if (!['smoke', 'warmup', 'load'].includes(mode)) throw new Error('잘못된 TEST_MODE');
const requestedStartMode = __ENV.START_MODE || 'simultaneous';
if (!['simultaneous', 'staggered'].includes(requestedStartMode)) throw new Error('잘못된 START_MODE');
// warm-up은 측정용 load의 시작 방식과 관계없이 동시 시작한다.
const startMode = mode === 'load' ? requestedStartMode : 'simultaneous';
// 기본 준비 시간은 유지하고, 새 JVM의 warm-up 시간 비교 시에만 변경한다.
const warmupSeconds = Number(__ENV.WARMUP_SECONDS || 20);
if (!Number.isInteger(warmupSeconds) || warmupSeconds < 1 || warmupSeconds > 600) {
  throw new Error('WARMUP_SECONDS는 1~600 사이의 정수(초)여야 합니다.');
}
const loadUsers = Number(__ENV.TEST_VUS || 10);
if (![10, 20, 50, 100, 150, 200].includes(loadUsers)) throw new Error('TEST_VUS는 10, 20, 50, 100, 150, 200이어야 합니다.');
const users = mode === 'smoke' ? 2 : loadUsers;
const measurementSeconds = Number(__ENV.MEASUREMENT_SECONDS || 60);
if (!Number.isInteger(measurementSeconds) || measurementSeconds < 60 || measurementSeconds > 300) {
  throw new Error('MEASUREMENT_SECONDS는 60~300 사이의 정수(초)여야 합니다.');
}
const p95 = __ENV.P95_MS ? Number(__ENV.P95_MS) : null;
if (p95 !== null && (!Number.isFinite(p95) || p95 <= 0)) throw new Error('P95_MS는 양수여야 합니다.');
const p99 = __ENV.P99_MS ? Number(__ENV.P99_MS) : null;
if (p99 !== null && (!Number.isFinite(p99) || p99 <= 0)) throw new Error('P99_MS는 양수여야 합니다.');
const thresholds = {
  checks: ['rate==1'],
  reservation_list_invalid: ['rate==0'],
  reservation_list_requests: [mode === 'smoke' ? 'count==20' : `count>=${users * 2}`],
  'http_req_failed{endpoint:reservation-list}': ['rate==0'],
  'reservation_list_requests{variant:all}': [mode === 'smoke' ? 'count==10' : `count>=${users}`],
  'reservation_list_requests{variant:reserved}': [mode === 'smoke' ? 'count==10' : `count>=${users}`],
};
// 성능 목표는 load에 명시한 경우에만 적용한다. warm-up의 시간 분포는 합격 기준에서 제외한다.
if (mode === 'load' && (p95 !== null || p99 !== null)) {
  for (const variant of ['all', 'reserved']) {
    thresholds[`reservation_list_${variant}_duration`] = [
      ...(p95 === null ? [] : [`p(95)<=${p95}`]),
      ...(p99 === null ? [] : [`p(99)<=${p99}`]),
    ];
  }
}
if (mode === 'load') {
  // 두 주요 구간에 실제 표본이 있어야 한다. drain 발생 여부는 합격 조건으로 강제하지 않는다.
  for (const phase of ['initial', 'later']) {
    for (const variant of ['all', 'reserved']) {
      const prefix = `reservation_list_${phase}_${variant}`;
      thresholds[`${prefix}_requests`] = ['count>0'];
      thresholds[`${prefix}_invalid`] = ['rate==0'];
    }
  }
}
export const options = {
  scenarios: mode === 'smoke' ? {
    smoke: { executor: 'per-vu-iterations', vus: 2, iterations: 5, maxDuration: '60s', gracefulStop: '5s' },
  } : {
    [mode]: { executor: 'constant-vus', vus: users, duration: `${mode === 'warmup' ? warmupSeconds : measurementSeconds}s`, gracefulStop: '25s' },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
  thresholds,
};

export function setup() {
  setupStartedAt.add(Date.now());
  if (fixture.length !== 400) throw new Error('DB 기대 결과는 회원 200명 × 조건 2개의 400행이어야 합니다.');
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
  if (accounts.length !== 200 || accounts.some(account => !account.expected.all || !account.expected.reserved)
    || new Set(accounts.map(account => account.email)).size !== 200) {
    throw new Error('서로 다른 회원 200명의 조건별 기대 결과가 필요합니다.');
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
  // 로그인 대기(setup 기본 한도 60초)와 종료 대기 25초까지 포함해 토큰 수명을 확인한다.
  const minimumValiditySeconds = mode === 'smoke' ? 120
    : (mode === 'warmup' ? warmupSeconds : measurementSeconds) + 60 + 25;
  for (const account of selected) account.token = login(account.email, minimumValiditySeconds);
  setupFinishedAt.add(Date.now());
  return selected;
}

export default function (accounts) {
  // Gauge의 min/max로 각 VU 첫 iteration 시작 시각의 범위를 확인한다.
  if (__ITER === 0) {
    vuStartedAt.add(Date.now());
    scenarioStartedAt.add(execution.scenario.startTime);
    if (startMode === 'staggered') {
      // VU ID 순서로 공통 scenario 시작부터 0~5초에 균등 배치한다. 매 iteration마다 쉬는 것은 아니다.
      const targetStart = execution.scenario.startTime + (__VU - 1) * 5000 / (users - 1);
      const remainingMs = targetStart - Date.now();
      if (remainingMs > 0) sleep(remainingMs / 1000);
    }
  }
  // given: VU마다 다른 회원과 DB에서 계산한 기대 결과를 사용한다.
  const account = accounts[__VU - 1];
  for (const variant of ['all', 'reserved']) {
    const expected = account.expected[variant];
    const filter = variant === 'reserved' ? '&status=RESERVED' : '';
    // setup이 끝난 뒤 시작하는 scenario가 공통 기준이다. VU마다 서로 다른 0초를 사용하지 않는다.
    const elapsedMs = Date.now() - execution.scenario.startTime;
    const phase = elapsedMs < 10000 ? 'initial' : elapsedMs < measurementSeconds * 1000 ? 'later' : 'drain';
    // when: 로그인은 측정 전에 마치고, 실제 Spring Security를 거쳐 조회한다.
    if (__ITER === 0 && variant === 'all') firstRequestAt.add(Date.now());
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
    if (mode === 'load') {
      phaseMetrics[phase][variant].duration.add(response.timings.duration);
      phaseMetrics[phase][variant].requests.add(1);
      phaseMetrics[phase][variant].invalid.add(!valid);
    }
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
  const lines = [`${mode} startMode=${startMode} 목록 요청: ${count}${mode === 'smoke' ? '/20' : ''}, 실패한 check: ${failed}`];
  for (const variant of ['all', 'reserved']) {
    const values = data.metrics[`reservation_list_${variant}_duration`]?.values;
    if (values && Number.isFinite(values.med)) lines.push(`${variant}: med=${values.med.toFixed(2)}ms p95=${values['p(95)'].toFixed(2)}ms p99=${values['p(99)'].toFixed(2)}ms`);
  }
  if (mode === 'load') {
    for (const phase of ['initial', 'later', 'drain']) {
      for (const variant of ['all', 'reserved']) {
        const prefix = `reservation_list_${phase}_${variant}`;
        const values = data.metrics[`${prefix}_duration`]?.values;
        const phaseCount = data.metrics[`${prefix}_requests`]?.values.count ?? 0;
        if (values && phaseCount > 0) {
          lines.push(`${phase}/${variant}: count=${phaseCount} p95=${values['p(95)'].toFixed(2)}ms p99=${values['p(99)'].toFixed(2)}ms`);
        } else {
          lines.push(`${phase}/${variant}: count=0`);
        }
      }
    }
  }
  return {
    stdout: lines.join('\n') + '\n',
    [`/results/${runId}/${mode === 'warmup' ? 'warmup-summary.json' : 'summary.json'}`]: JSON.stringify(summary, null, 2),
  };
}
